(ns pulse.gen
  (:require [clojure.string :as str])
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util])
  (:require [pulse.queue :as queue])
  (:require [pulse.pipe :as pipe])
  (:require [pulse.parse :as parse]))

(set! *warn-on-reflection* true)

(def rd
  (redis/init {:url conf/redis-url}))

(def publish-queue
  (queue/init 100))

(def process-queue
  (queue/init 10000))

(defn update [m k f]
  (assoc m k (f (get m k))))

(defn safe-inc [n]
  (inc (or n 0)))

(def calcs
  (atom []))

(def ticks
  (atom []))

(defn init-stat [s-key calc tick]
  (util/log "gen init_stat stat-key=%s" s-key)
  (swap! calcs conj calc)
  (swap! ticks conj tick))

(defn init-count-stat [s-key v-time b-time t-fn]
  (let [sec-counts-a (atom [0 {}])]
    (init-stat s-key
      (fn [evt]
        (if (t-fn evt)
          (swap! sec-counts-a
            (fn [[sec sec-counts]]
              [sec (update sec-counts sec safe-inc)]))))
      (fn []
        (let [[sec sec-counts] @sec-counts-a
              count (reduce + (vals sec-counts))
              r (long (/ count (/ b-time v-time)))]
          (queue/offer publish-queue [s-key r])
          (swap! sec-counts-a
            (fn [[sec sec-counts]]
              [(inc sec) (dissoc sec-counts (- sec b-time))])))))))

(defn init-count-sec-stat [s-key t-fn]
  (init-count-stat s-key 1 10 t-fn))

(defn init-count-min-stat [s-key t-fn]
  (init-count-stat s-key 60 60 t-fn))

(defn init-count-top-stat [s-key v-time b-time k-size t-fn k-fn]
  (let [sec-key-counts-a (atom [0 {}])]
    (init-stat s-key
      (fn [evt]
        (if (t-fn evt)
          (let [k (k-fn evt)]
            (swap! sec-key-counts-a
              (fn [[sec sec-key-counts]]
                [sec (update-in sec-key-counts [sec k] safe-inc)])))))
      (fn []
        (let [[sec sec-key-counts] @sec-key-counts-a
              counts (apply merge-with + (vals sec-key-counts))
              sorted (sort-by (fn [[k kc]] (- kc)) counts)
              highs  (take k-size sorted)
              normed (map (fn [[k kc]] [k (long (/ kc (/ b-time v-time)))]) highs)]
          (queue/offer publish-queue [s-key normed])
          (swap! sec-key-counts-a
            (fn [[sec sec-key-counts]]
              [(inc sec) (dissoc sec-key-counts (- sec b-time))])))))))

(defn init-count-top-sec-stat [s-key t-fn k-fn]
  (init-count-top-stat s-key 1 10 5 t-fn k-fn))

(defn init-count-top-min-stat [s-key t-fn k-fn]
  (init-count-top-stat s-key 60 60 5 t-fn k-fn))

(defn init-last-stat [s-key t-fn v-fn]
  (let [last-a (atom nil)]
    (init-stat s-key
      (fn [evt]
        (if (t-fn evt)
          (let [v (v-fn evt)]
            (swap! last-a (constantly v)))))
      (fn []
        (let [v @last-a]
          (queue/offer publish-queue [s-key v]))))))

(defn init-stats []
  (util/log "gen init_stats")

  (init-count-sec-stat "events_per_second"
    (fn [evt] true))

  (init-count-sec-stat "events_internal_per_second"
    (fn [evt] (= (:cloud evt) "heroku.com")))

  (init-count-sec-stat "events_external_per_second"
    (fn [evt] (and (:cloud evt) (not= (:cloud evt) "heroku.com"))))

  (init-count-sec-stat "events_unparsed_per_second"
    (fn [evt] (not (:parsed evt))))

  (init-count-sec-stat "nginx_requests_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "nginx_access")
                   (not= (:http_host evt) "127.0.0.1"))))

  (init-count-top-sec-stat "nginx_requests_by_domain_per_second"
    (fn [evt]  (and (= (:cloud evt) "heroku.com")
                    (= (:event_type evt) "nginx_access")
                    (not= (:http_host evt) "127.0.0.1")))
    (fn [evt] (:http_domain evt)))

  (init-count-min-stat "nginx_errors_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "nginx_error"))))

  (init-count-top-min-stat "nginx_50x_by_domain_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "nginx_access")
                   (not= (:http_host evt) "127.0.0.1")
                   (>= (:http_status evt) 500)))
    (fn [evt] (:http_domain evt)))

 (doseq [s [500 502 503 504]]
    (init-count-min-stat (str "nginx_" s "_per_minute")
      (fn [evt] (and (= (:cloud evt) "heroku.com")
                     (= (:event_type evt) "nginx_access")
                     (not= (:http_host evt) "127.0.0.1")
                     (= (:http_status evt) s)))))

  (init-count-sec-stat "varnish_requests_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "varnish_access"))))

  (init-count-sec-stat "hermes_requests_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "hermes_access")
                   (:domain evt))))

  (doseq [e ["H10" "H11" "H12" "H13" "H99"]]
    (init-count-min-stat (str "hermes_" e "_per_minute")
      (fn [evt] (and (= (:cloud evt) "heroku.com")
                     (= (:event_type evt) "hermes_access")
                     (:Error evt)
                     (get evt (keyword e))))))

  (init-count-sec-stat "ps_converges_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:converge_service evt))))

  (init-count-min-stat "ps_run_requests_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_publish evt)
                   (:exchange evt)
                   (re-find #"(ps\.run)|(service\.needed)" (:exchange evt)))))

  (init-count-min-stat "ps_stop_requests_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_publish evt)
                   (:exchange evt)
                   (re-find #"ps\.kill\.\d+" (:exchange evt)))))

  (init-count-min-stat "ps_kll_requests_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:railgun_service evt)
                   (:ps_kill evt)
                   (= (:reason evt) "load"))))

  (init-count-min-stat "ps_runs_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:railgun_ps_watch evt)
                   (:invoke_ps_run evt))))

  (init-count-min-stat "ps_returns_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:railgun_ps_watch evt)
                   (:handle_ps_return evt))))

  (init-count-min-stat "ps_traps_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:railgun_ps_watch evt)
                   (:trap_exit evt))))

  (init-last-stat "ps_lost"
    (fn [evt] (:process_lost evt))
    (fn [evt] (:total_count evt)))

  (doseq [[k p] [["invokes" (fn [evt] (:invoke evt))]
                 ["fails"   (fn [evt] (or (:compile_error evt)
                                          (:locked_error evt)))]
                 ["errors"  (fn [evt] (or (:publish_error evt)
                                          (:unexpected_error evt)))]]]
    (init-count-min-stat (str "slugc_" k "_per_minute")
      (fn [evt] (and (= (:cloud evt) "heroku.com")
                     (:slugc_bin evt)
                     (p evt)))))

  (init-count-sec-stat "amqp_publishes_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_publish evt))))

  (init-count-sec-stat "amqp_receives_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_message evt)
                   (= (:action evt) "received"))))

  (init-count-min-stat "amqp_timeouts_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_message evt)
                   (= (:action evt) "timeout"))))

  (init-count-top-sec-stat "amqp_publishes_by_exchange_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_publish evt)))
    (fn [evt] (:exchange evt)))

  (init-count-top-sec-stat "amqp_receives_by_exchange_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_message evt)
                   (= (:action evt) "received")))
    (fn [evt] (:exchange evt)))

  (init-count-top-min-stat "amqp_timeout_by_exchange_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_message evt)
                   (= (:action evt) "timeout")))
    (fn [evt] (:exchange evt))))

(defn calc [evt]
  (doseq [calc @calcs]
    (calc evt)))

(defn init-ticker []
  (util/log "gen init_ticker")
  (let [start (System/currentTimeMillis)]
    (util/spawn-tick 1000
      (fn []
        (util/log "gen tick elapsed=%d" (- (System/currentTimeMillis) start))
        (doseq [tick @ticks]
          (tick))
        (queue/offer publish-queue ["redraw" true])))))

(defn parse [line forwarder-host]
  (if-let [evt (parse/parse-line line)]
    (assoc evt :line line :forwarder_host forwarder-host :parsed true)
    {:line line :forwarder_host forwarder-host :parsed false}))

(defn init-tailers []
  (util/log "gen init_tailers")
  (doseq [forwarder-host conf/forwarder-hosts]
    (util/log "gen init_tailer forwarder_host=%s" forwarder-host)
    (util/spawn (fn []
       (pipe/shell-lines ["ssh" (str "ubuntu@" forwarder-host) "sudo" "tail" "-f" "/var/log/heroku/US/Pacific/log"]
         (fn [line]
           (queue/offer process-queue [line forwarder-host])))))))

(defn init-processors []
  (util/log "gen init_processors")
  (dotimes [i 2]
    (util/log "gen init_processor index=%d" i)
    (util/spawn-loop
      (fn []
        (let [[line forwarder] (queue/take process-queue)]
          (calc (parse line forwarder)))))))

(defn init-publishers []
  (util/log "gen init_publishers")
  (dotimes [i 8]
    (util/log "gen init_publisher index=%d" i)
    (util/spawn-loop
      (fn []
        (let [[k v] (queue/take publish-queue)]
           (util/log "gen publish pub_key=stats stat-key=%s" k)
           (redis/publish rd "stats" (json/generate-string [k v])))))))

(defn init-watcher []
  (util/log "gen init_watcher")
  (let [start (System/currentTimeMillis)]
    (util/spawn-tick 1000
      (fn []
        (let [elapsed (/ (- (System/currentTimeMillis) start) 1000.0)
              [r-pushed r-dropped r-depth] (queue/stats process-queue)
              [u-pushed u-dropped u-depth] (queue/stats publish-queue)]
          (util/log "gen watch elapsed=%.3f process_pushed=%d process_dropped=%d process_depth=%d publish_pushed=%d publish_dropped=%d publish_depth=%d"
            elapsed r-pushed r-dropped r-depth u-pushed u-dropped u-depth))))))

(defn -main []
  (init-stats)
  (init-ticker)
  (init-watcher)
  (init-publishers)
  (init-processors)
  (init-tailers))