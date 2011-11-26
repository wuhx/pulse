(ns pulse.def
  (:refer-clojure :exclude [last max])
  (:require [clojure.set :as set]
            [pulse.util :as util]
            [pulse.conf :as conf]))

(defn safe-inc [n]
  (inc (or n 0)))

(defn sum [c]
  (reduce + c))

(defn max [time-buffer pred-fn val-fn]
  {:receive-init
     (fn []
       [(util/millis) nil])
   :receive-apply
     (fn [[window-start window-max :as window] evt]
       (if-not (pred-fn evt)
         window
         (let [val (val-fn evt)]
           (cond
             (nil? window-max)  [window-start val]
             (> val window-max) [window-start val]
             :else              window))))
   :receive-emit
     (fn [receive-buffer]
       receive-buffer)
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows [window-start window-max :as window]]
       (conj windows window))
   :merge-emit
     (fn [windows]
       (let [now (util/millis)
             recent-windows (filter (fn [[window-start _]] (>= window-start (- now (* 1000 time-buffer)))) windows)
             recent-values (->> windows (map (fn [[_ window-max]] window-max)) (filter #(not (nil? %))))
             recent-max (or (and (seq recent-values) (apply clojure.core/max recent-values)) 0)]
         [recent-windows recent-max]))})

(defn mean [time-buffer pred-fn val-fn]
  {:receive-init
     (fn []
       [(util/millis) 0 0])
   :receive-apply
     (fn [[window-start window-count window-sum :as receive-buffer] event]
       (if (pred-fn event)
         [window-start (inc window-count) (+ window-sum (val-fn event))]
         receive-buffer))
   :receive-emit
     (fn [receive-buffer]
       receive-buffer)
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows window]
       (conj windows window))
   :merge-emit
     (fn [windows]
       (let [now (util/millis)
             recent-windows (filter (fn [[window-start _ _]] (>= window-start (- now (* 1000 time-buffer)))) windows)
             recent-count (sum (map (fn [[_ window-count _]] window-count) recent-windows))
             recent-sum (sum (map (fn [[_ _ window-sum]] window-sum) recent-windows))
             recent-mean (double (if (zero? recent-count) 0 (/ recent-sum recent-count)))]
         [recent-windows recent-mean]))})

(defn rate [time-unit time-buffer pred-fn]
  {:receive-init
     (fn []
       [(util/millis) 0])
   :receive-apply
     (fn [[window-start window-count] event]
       [window-start (if (pred-fn event) (inc window-count) window-count)])
   :receive-emit
     (fn [[window-start window-count]]
       [window-start (util/millis) window-count])
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows window]
       (conj windows window))
   :merge-emit
     (fn [windows]
       (let [now (util/millis)
             recent-windows (filter (fn [[window-start _ _]] (>= window-start (- now (* 1000 time-buffer) 1000))) windows)
             complete-windows (filter (fn [[window-start _ _]] (< window-start (- now 1000))) recent-windows)
             complete-count (sum (map (fn [[_ _ window-count]] window-count) complete-windows))
             complete-rate (double (/ complete-count (/ time-buffer time-unit)))]
         [recent-windows complete-rate]))})

(defn per-second [pred-fn]
  (rate 1 10 pred-fn))

(defn per-minute [pred-fn]
  (rate 60 70 pred-fn))

(defn rate-by-key [time-unit time-buffer pred-fn key-fn]
  {:receive-init
     (fn []
       [(util/millis) {}])
   :receive-apply
     (fn [[window-start window-counts] event]
       [window-start (if (pred-fn event) (util/update window-counts (str (key-fn event)) safe-inc) window-counts)])
   :receive-emit
     (fn [[window-start window-counts]]
       [window-start (util/millis) window-counts])
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows window]
       (conj windows window))
   :merge-emit
     (fn [windows]
        (let [now (util/millis)
              recent-windows (filter (fn [[window-start _ _]] (>= window-start (- now (* 1000 time-buffer) 1000))) windows)
              complete-windows (filter (fn [[window-start _ _]] (< window-start (- now 1000))) recent-windows)
              complete-counts (apply merge-with + (map (fn [[_ _ window-counts]] window-counts) complete-windows))
              complete-sorted-counts (sort-by (fn [[k kc]] (- kc)) complete-counts)
              complete-high-counts (take 10 complete-sorted-counts)
              complete-rates (map (fn [[k kc]] [k (double (/ kc (/ time-buffer time-unit)))]) complete-high-counts)]
          [recent-windows complete-rates]))})

(defn per-second-by-key [pred-fn key-fn]
  (rate-by-key 1 10 pred-fn key-fn))

(defn per-minute-by-key [pred-fn key-fn]
  (rate-by-key 60 70 pred-fn key-fn))

(defn rate-unique [time-buffer pred-fn key-fn]
  {:receive-init
     (fn []
       [(util/millis) #{}])
   :receive-apply
     (fn [[window-start window-hits] event]
       [window-start (if (pred-fn event) (conj window-hits (key-fn event)) window-hits)])
   :receive-emit
     (fn [window]
       window)
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows window]
       (conj windows window))
   :merge-emit
     (fn [windows]
        (let [now (util/millis)
              recent-windows (filter (fn [[window-start _]] (>= window-start (- now (* 1000 time-buffer)))) windows)
              recent-hits (apply set/union (map (fn [[_ window-hits]] window-hits) recent-windows))
              recent-count (count recent-hits)]
          [recent-windows recent-count]))})

(defn per-minute-unique [pred-fn key-fn]
  (rate-unique 60 pred-fn key-fn))

(defn last [pred-fn val-fn]
  {:receive-init
     (fn []
       nil)
   :receive-apply
     (fn [last-val event]
       (if (pred-fn event)
         (val-fn event)
         last-val))
   :receive-emit
     (fn [last-val]
       last-val)
   :merge-init
     (fn []
       nil)
   :merge-apply
     (fn [last-val received]
       (or received last-val))
   :merge-emit
     (fn [last-val]
       [last-val last-val])})

(defn last-sum [pred-fn part-fn val-fn & [recent-interval]]
  {:receive-init
     (fn []
       {})
   :receive-apply
     (fn [last-timed-vals event]
       (if (pred-fn event)
         (assoc last-timed-vals (part-fn event) [(util/millis) (val-fn event)])
         last-timed-vals))
   :receive-emit
     (fn [last-timed-vals]
       last-timed-vals)
   :merge-init
     (fn []
       {})
   :merge-apply
     (fn [last-timed-vals received]
       (merge last-timed-vals received))
   :merge-emit
     (fn [last-timed-vals]
       (let [now (util/millis)
             recent-timed-vals (into {} (filter (fn [[_ [last-time _]]] (< (- now last-time) (* (or recent-interval 300) 1000))) last-timed-vals))
             recent-sum (sum (map (fn [[_ [_ last-val]]] last-val) recent-timed-vals))]
         [recent-timed-vals recent-sum]))})

(defmacro defstat [stat-name stat-body]
  (let [stat-name-str (name stat-name)]
    `(def ~stat-name (merge ~stat-body {:name (name ~stat-name-str)}))))

(defn kv? [m k v]
  (= (k m) v))

(def k?
  contains?)

(defn cloud? [evt]
  (= (:cloud evt) (conf/cloud)))

; global

(defstat events-per-second
  (per-second
    (fn [evt] true)))

(defstat events-per-second-by-source
  (per-second-by-key
    (fn [evt] (:source evt))
    (fn [evt] (:source evt))))

(defstat events-per-second-unparsed
  (per-second
    (fn [evt]
      (when (:unparsed evt)
        (println "unparsed:" (:line evt))
        true))))

(defstat amqp-publishes-per-second
  (per-second
    (fn [evt] (and (cloud? evt) (or (:amqp_publish evt) (and (:amqp_message evt) (= (:action evt) "publish")))))))

(defstat amqp-receives-per-second
  (per-second
    (fn [evt] (and (cloud? evt) (:amqp_message evt) (= (:action evt) "received")))))

(defstat amqp-timeouts-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:amqp_message evt) (= (:action evt) "timeout")))))

(defstat amqp-publishes-per-second-by-exchange
  (per-second-by-key
    (fn [evt] (and (cloud? evt) (:amqp_publish evt)))
    (fn [evt] (:exchange evt))))

(defstat amqp-receives-per-second-by-exchange
  (per-second-by-key
    (fn [evt] (and (cloud? evt) (:amqp_message evt) (= (:action evt) "received")))
    (fn [evt] (:exchange evt))))

(defstat amqp-timeouts-per-minute-by-exchange
  (per-minute-by-key
    (fn [evt] (and (cloud? evt) (:amqp_message evt) (= (:action evt) "timeout")))
    (fn [evt] (:exchange evt))))

; routing

(defn nginx-request? [evt]
  (and (and (cloud? evt) (kv? evt :source "nginx") (k? evt :http_status))))

(defstat nginx-requests-per-second
  (per-second nginx-request?))

(defstat nginx-requests-per-second-by-domain
  (per-second-by-key nginx-request? :http_domain))

(defstat nginx-requests-domains-per-minute
  (per-minute-unique nginx-request? :http_domain))

(defn nginx-per-minute [status]
  (per-minute
    (fn [evt] (and (nginx-request? evt)
                   (not (kv? evt :http_host "127.0.0.1"))
                   (kv? evt :http_status status)))))

(defstat nginx-500-per-minute
  (nginx-per-minute 500))

(defstat nginx-502-per-minute
  (nginx-per-minute 502))

(defstat nginx-503-per-minute
  (nginx-per-minute 503))

(defstat nginx-504-per-minute
  (nginx-per-minute 504))

(defn nginx-domains-per-minute [status]
  (per-minute-unique
    (fn [evt] (and (nginx-request? evt)
                   (not (kv? evt :http_host "127.0.0.1"))
                   (kv? evt :http_status status)))
    :http_domain))

(defstat nginx-500-domains-per-minute
  (nginx-domains-per-minute 500))

(defstat nginx-502-domains-per-minute
  (nginx-domains-per-minute 502))

(defstat nginx-503-domains-per-minute
  (nginx-domains-per-minute 503))

(defstat nginx-504-domains-per-minute
  (nginx-domains-per-minute 504))

(defn nginx-error? [evt]
  (and (cloud? evt)
       (kv? evt :source "nginx")
       (kv? evt :level "crit")
       (:msg evt) (.contains (:msg evt) "[error]")))

(defstat nginx-errors-per-minute
  (per-minute nginx-error?))

(defstat nginx-errors-instances-per-minute
  (per-minute-unique nginx-error? :instance_id))

(defn varnish-request? [evt]
  (and (cloud? evt) (kv? evt :source "varnish") (k? evt :http_status)))

(defstat varnish-requests-per-second
  (per-second varnish-request?))

(defn varnish-per-minute [status]
  (per-minute
    (fn [evt] (and (varnish-request? evt) (kv? evt :http_status status)))))

(defstat varnish-500-per-minute
  (varnish-per-minute 500))

(defstat varnish-502-per-minute
  (varnish-per-minute 502))

(defstat varnish-503-per-minute
  (varnish-per-minute 503))

(defstat varnish-504-per-minute
  (varnish-per-minute 504))

(defstat varnish-purges-per-minute
  (per-minute
     (fn [evt] (and (cloud? evt) (:cache_purge evt)))))

(defstat rendezvous-joins-per-minute
  (per-minute
    (fn [evt]
      (and (:rendezvous evt) (:join evt)))))

(defstat rendezvous-rendezvous-per-minute
  (per-minute
    (fn [evt]
      (and (:rendezvous evt) (:conn_id evt) (:waiting_id evt)))))

(defstat hermes-requests-per-second
  (per-second
    (fn [evt] (and (cloud? evt) (:hermes_proxy evt)))))

(defstat hermes-requests-apps-per-minute
  (per-minute-unique
    (fn [evt] (and (cloud? evt) (:hermes_proxy evt)))
    (fn [evt] (:app_id evt))))

(defstat hermes-requests-per-second-by-app-id
  (per-second-by-key
    (fn [evt] (and (cloud? evt) (:hermes_proxy evt)))
    (fn [evt] (:app_id evt))))

(defstat hermes-requests-per-second-by-instance-id
  (per-second-by-key
    (fn [evt] (and (cloud? evt) (:hermes_proxy evt)))
    (fn [evt] (:instance_id evt))))

(defn hermes-per-minute [code]
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :hermes_proxy) (kv? evt :code code)))))

(defstat hermes-h10-per-minute
  (hermes-per-minute "H10"))

(defstat hermes-h11-per-minute
  (hermes-per-minute "H11"))

(defstat hermes-h12-per-minute
  (hermes-per-minute "H12"))

(defstat hermes-h13-per-minute
  (hermes-per-minute "H13"))

(defstat hermes-h14-per-minute
  (hermes-per-minute "H14"))

(defstat hermes-h99-per-minute
  (hermes-per-minute "H99"))

(defn hermes-apps-per-minute [code]
  (per-minute-unique
    (fn [evt] (and (cloud? evt) (k? evt :hermes_proxy) (kv? evt :code code)))
    (fn [evt] (:app_id evt))))

(defstat hermes-h10-apps-per-minute
  (hermes-apps-per-minute "H10"))

(defstat hermes-h11-apps-per-minute
  (hermes-apps-per-minute "H11"))

(defstat hermes-h12-apps-per-minute
  (hermes-apps-per-minute "H12"))

(defstat hermes-h13-apps-per-minute
  (hermes-apps-per-minute "H13"))

(defstat hermes-h14-apps-per-minute
  (hermes-apps-per-minute "H14"))

(defstat hermes-h99-apps-per-minute
  (hermes-apps-per-minute "H99"))

(defstat hermes-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :level "err") (kv? evt :source "hermes")))))

(defstat hermes-lockstep-updates-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:services_callback evt) (:txid evt)))))

(defstat hermes-lockstep-connections-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:services_callback evt) (= (:event evt) "connect")))))

(defstat hermes-lockstep-disconnections-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:services_callback evt) (= (:event evt) "disconnect")))))

(defstat hermes-lockstep-mean-latency
  (mean 70
    (fn [evt] (and (cloud? evt) (:hermes_clock evt) (:STATS evt) (:last_service_latency evt)))
    (fn [evt] (:last_service_latency evt))))

(defstat hermes-lockstep-max-latency
  (max 70
    (fn [evt] (and (cloud? evt) (:hermes_clock evt) (:STATS evt) (:last_service_latency evt)))
    (fn [evt] (:last_service_latency evt))))

(defstat hermes-lockstep-mean-stillness
  (mean 70
    (fn [evt] (and (cloud? evt) (:hermes_clock evt) (:STATS evt) (:last_service_update evt)))
    (fn [evt] (:last_service_update evt))))

(defstat hermes-lockstep-max-stillness
  (max 70
    (fn [evt] (and (cloud? evt) (:hermes_clock evt) (:STATS evt) (:last_service_update evt)))
    (fn [evt] (:last_service_update evt))))

(defstat hermes-elevated-route-lookups-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:hermes_proxy evt) (= (:code evt) "OK") (>= (:route evt) 2.0)))))

(defstat hermes-slow-route-lookups-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:hermes_proxy evt) (= (:code evt) "OK") (>= (:route evt) 10.0)))))

(defstat hermes-catastrophic-route-lookups-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:hermes_proxy evt) (= (:code evt) "OK") (>= (:route evt) 100.0)))))

(defstat hermes-slow-redis-lookups-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :source "hermes") (:redis_helper evt) (>= (:time evt) 10.0)))))

(defstat hermes-catastrophic-redis-lookups-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :source "hermes") (:redis_helper evt) (>= (:time evt) 25.0)))))

(defstat hermes-processes-last
  (last-sum
    (fn [evt] (and (cloud? evt) (:hermes_clock evt) (:STATS evt)))
    (fn [evt] (:instance_id evt))
    (fn [evt] (:processes evt))))

(defstat hermes-ports-last
  (last-sum
    (fn [evt] (and (cloud? evt) (:hermes_clock evt) (:STATS evt)))
    (fn [evt] (:instance_id evt))
    (fn [evt] (:ports evt))))

; runtime

(defstat railgun-unhandled-exceptions-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:railgun evt) (:exception evt) (not (:site evt)) (not (:reraise evt))))))

(defstat ps-up-total-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "counts") (= (:event evt) "emit")))
    (fn [evt] (:up evt))))

(defstat ps-up-web-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "counts") (= (:event evt) "emit")))
    (fn [evt] (:web evt))))

(defstat ps-up-worker-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "counts") (= (:event evt) "emit")))
    (fn [evt] (:worker evt))))

(defstat ps-up-other-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "counts") (= (:event evt) "emit")))
    (fn [evt] (:other evt))))

(defstat ps-created-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "counts") (= (:event evt) "emit")))
    (fn [evt] (:created evt))))

(defstat ps-starting-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "counts") (= (:event evt) "emit")))
    (fn [evt] (:starting evt))))

(defstat ps-idles-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "up_to_up") (= (:event evt) "idle")))))

(defstat ps-unidles-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "unidle") (= (:block evt) "begin")))))

(defstat ps-crashed-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "counts") (= (:event evt) "emit")))
    (fn [evt] (:crashed evt))))

(defstat ps-running-total-last
  (last-sum
    (fn [evt] (and (cloud? evt) (:railgun evt) (:counts evt) (= (:key evt) "total")))
    (fn [evt] (:instance_id evt))
    (fn [evt] (:num evt))))

(defstat ps-running-web-last
  (last-sum
    (fn [evt] (and (cloud? evt) (:railgun evt) (:counts evt) (= (:key evt) "process_type") (= (:process_type evt) "web")))
    (fn [evt] (:instance_id evt))
    (fn [evt] (:num evt))))

(defstat ps-running-worker-last
  (last-sum
    (fn [evt] (and (cloud? evt) (:railgun evt) (:counts evt) (= (:key evt) "process_type") (= (:process_type evt) "worker")))
    (fn [evt] (:instance_id evt))
    (fn [evt] (:num evt))))

(defstat ps-running-other-last
  (last-sum
    (fn [evt] (and (cloud? evt) (:railgun evt) (:counts evt) (= (:key evt) "process_type") (= (:process_type evt) "other")))
    (fn [evt] (:instance_id evt))
    (fn [evt] (:num evt))))

(defstat ps-run-requests-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:amqp_publish evt) (= (:exchange evt) "ps.run")))))

(defstat ps-runs-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:ps_watch evt) (:ps_run evt) (= (:at evt) "start")))))

(defstat ps-returns-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:ps_watch evt) (:ps_run evt) (= (:at evt) "exit")))))

(defstat ps-stop-requests-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:amqp_publish evt) (:exchange evt) (re-find #"ps\.kill\.\d+" (:exchange evt))))))

(defstat ps-stops-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:ps_watch evt) (:trap_exit evt)))))

(defstat ps-converges-per-second
  (per-second
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "transition") (= (:block evt) "begin")))))

(defstat ps-timeouts-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:monitor_boot evt) (= (:at evt) "timeout")))))

(defstat ps-launch-time-mean
  (mean 60
    (fn [evt] (and (cloud? evt) (:monitor_boot evt) (= (:at evt) "responsive")))
    (fn [evt] (:age evt))))

(defstat ps-lost-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (= (:function evt) "counts") (= (:event evt) "emit")))
    (fn [evt] (:lost evt))))

(defstat psmgr-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (= (:level evt) "err") (kv? evt :source "psmgr")))))

; packaging

(defstat gitproxy-connections-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:gitproxy evt) (:run evt) (= (:at evt) "start")))))

(defstat gitproxy-invalids-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:gitproxy evt) (:run evt) (= (:at evt) "invalid")))))

(defstat gitproxy-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:gitproxy evt) (:run evt) (= (:at evt) "exception") (not (:reraise evt))))))

(defstat gitproxy-successes-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:gitproxy evt) (:run evt) (= (:at evt) "success")))))

(defstat gitproxy-mean-metadata-time
  (mean 60
    (fn [evt] (and (cloud? evt) (:gitproxy evt) (:fetch_push_metadata evt) (= (:at evt) "finish")))
    (fn [evt] (:elapsed evt))))

(defstat gitproxy-mean-provision-time
  (mean 60
    (fn [evt] (and (cloud? evt) (:gitproxy evt) (:fetch_ssh_info evt) (= (:backend evt) "codon") (= (:at evt) "finish")))
    (fn [evt] (:elapsed evt))))

(defstat gitproxy-mean-service-time
  (mean 60
    (fn [evt] (and (cloud? evt) (:gitproxy evt) (:run evt) (= (:at evt) "finish")))
    (fn [evt] (:elapsed evt))))

(defstat codon-launches-per-minute
  (per-minute
    (fn [evt] (and (:codon evt) (:production evt) (:monitor_processes evt) (= (:at evt) "launch")))))

(defstat codon-receives-per-minute
  (per-minute
    (fn [evt] (and (:codon evt) (:production evt) (:receive evt) (= (:at evt) "dequeue") (not (:timeout evt))))))

(defstat codon-exits-per-minute
  (per-minute
    (fn [evt] (and (:codon evt) (:production evt) (:receive evt) (= (:at evt) "exit")))))

(defstat codon-cycles-per-minute
  (per-minute
    (fn [evt] (and (:codon evt) (:production evt) (:receive evt) (= (:at evt) "cycle")))))

(defstat codon-up-last
  (last-sum
    (fn [evt] (and (:codon evt) (:production evt) (:spawn_heartbeat evt) (= (:at evt) "emit")))
    (fn [evt] (:hostname evt))
    (fn [evt] 1)
    3))

(defstat codon-busy-last
  (last-sum
    (fn [evt] (and (:codon evt) (:production evt) (:spawn_heartbeat evt) (= (:at evt) "emit")))
    (fn [evt] (:hostname evt))
    (fn [evt] (if (:busy evt) 1 0))
    3))

(defstat codon-compiling-last
  (last-sum
    (fn [evt] (and (:codon evt) (:production evt) (:spawn_heartbeat evt) (= (:at evt) "emit")))
    (fn [evt] (:hostname evt))
    (fn [evt] (if (:compiling evt) 1 0))
    3))

(defstat codon-mean-fetch-time
  (mean 60
    (fn [evt] (and (:codon evt) (:production evt) (:fetch_repo evt) (= (:at evt) "finish")))
    (fn [evt] (:elapsed evt))))

(defstat codon-mean-stow-time
  (mean 60
    (fn [evt] (and (:codon evt) (:production evt) (:stow_repo evt) (= (:at evt) "finish")))
    (fn [evt] (:elapsed evt))))

(defstat codon-fetch-errors-per-minute
  (per-minute
    (fn [evt] (and (:codon evt) (:production evt) (:fetch_repo evt) (= (:at evt) "error")))))

(defstat codon-stow-errors-per-minute
  (per-minute
    (fn [evt] (and (:codon evt) (:production evt) (:stow_repo evt) (= (:at evt) "finish") (not= (:exit_status evt) 0) (not= (:out evt) "200")))))

(defstat codon-mean-service-time
  (mean 60
    (fn [evt] (and (:codon evt) (:production evt) (:await evt) (= (:at evt) "finish") (:service_elapsed evt)))
    (fn [evt] (:service_elapsed evt))))

(defstat codon-mean-age
  (mean 60
    (fn [evt] (and (:codon evt) (:production evt) (:spawn_heartbeat evt) (= (:at evt) "emit")))
    (fn [evt] (:age evt))))

(defstat codon-unhandled-exceptions-per-minute
  (per-minute
    (fn [evt] (and (:codon evt) (:production evt) (:exception evt) (not (:site evt)) (not (:reraise evt))))))

(defstat slugc-compiles-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:bin evt) (= (:event evt) "start")))))

(defstat slugc-aspen-compiles-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:bin evt) (= (:event evt) "start") (= (:major_stack evt) "aspen")))))

(defstat slugc-bamboo-compiles-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:bin evt) (= (:event evt) "start") (= (:major_stack evt) "bamboo")))))

(defstat slugc-cedar-compiles-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:bin evt) (= (:event evt) "start") (= (:major_stack evt) "cedar")))))

(defstat slugc-failures-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:bin evt) (= (:event evt) "fail")))))

(defstat slugc-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:bin evt) (= (:event evt) "error")))))

(defstat slugc-successes-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:bin evt) (= (:event evt) "finish")))))

(defstat slugc-mean-stow-time
  (mean 60
    (fn [evt] (and (cloud? evt) (:slugc evt) (:store_in_s3 evt) (= (:event evt) "finish")))
    (fn [evt] (:elapsed evt))))

(defstat slugc-mean-release-time
  (mean 60
    (fn [evt] (and (cloud? evt) (:slugc evt) (:post_release evt) (= (:event evt) "finish")))
    (fn [evt] (:elapsed evt))))

(defstat slugc-stow-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:store_in_s3 evt) (= (:event evt) "error")))))

(defstat slugc-release-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (:slugc evt) (:post_release evt) (= (:event evt) "error")))))

(defstat slugc-mean-compile-time
  (mean 60
    (fn [evt] (and (cloud? evt) (:slugc evt) (:bin evt) (= (:event evt) "finish")))
    (fn [evt] (:elapsed evt))))

(defstat codex-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (= (:level evt) "err") (kv? evt :source "codex")))))

; api

(defstat releases-per-minute
  (per-minute
    (fn [evt]
      (and (cloud? evt) (:capture_release evt)))))

(defstat api-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (= (:level evt) "err") (kv? evt :source "core")))))

; data

(defstat shen-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (= (:level evt) "err") (kv? evt :source "shen")))))

(def all
  [
  ; global
   events-per-second
   events-per-second-by-source
   events-per-second-unparsed
   amqp-publishes-per-second
   amqp-receives-per-second
   amqp-timeouts-per-minute
   amqp-publishes-per-second-by-exchange
   amqp-receives-per-second-by-exchange
   amqp-timeouts-per-minute-by-exchange

   ; routing
   nginx-requests-per-second
   nginx-requests-per-second-by-domain
   nginx-requests-domains-per-minute
   nginx-500-per-minute
   nginx-502-per-minute
   nginx-503-per-minute
   nginx-504-per-minute
   nginx-500-domains-per-minute
   nginx-502-domains-per-minute
   nginx-503-domains-per-minute
   nginx-504-domains-per-minute
   nginx-errors-per-minute
   nginx-errors-instances-per-minute
   varnish-requests-per-second
   varnish-500-per-minute
   varnish-502-per-minute
   varnish-503-per-minute
   varnish-504-per-minute
   varnish-purges-per-minute
   rendezvous-joins-per-minute
   rendezvous-rendezvous-per-minute
   hermes-requests-per-second
   hermes-requests-apps-per-minute
   hermes-requests-per-second-by-app-id
   hermes-requests-per-second-by-instance-id
   hermes-h10-per-minute
   hermes-h11-per-minute
   hermes-h12-per-minute
   hermes-h13-per-minute
   hermes-h14-per-minute
   hermes-h99-per-minute
   hermes-h10-apps-per-minute
   hermes-h11-apps-per-minute
   hermes-h12-apps-per-minute
   hermes-h13-apps-per-minute
   hermes-h14-apps-per-minute
   hermes-h99-apps-per-minute
   hermes-errors-per-minute
   hermes-lockstep-updates-per-minute
   hermes-lockstep-connections-per-minute
   hermes-lockstep-disconnections-per-minute
   hermes-lockstep-mean-latency
   hermes-lockstep-max-latency
   hermes-lockstep-mean-stillness
   hermes-lockstep-max-stillness
   hermes-elevated-route-lookups-per-minute
   hermes-slow-route-lookups-per-minute
   hermes-catastrophic-route-lookups-per-minute
   hermes-slow-redis-lookups-per-minute
   hermes-catastrophic-redis-lookups-per-minute
   hermes-processes-last
   hermes-ports-last

   ; runtime
   railgun-unhandled-exceptions-per-minute
   ps-up-total-last
   ps-up-web-last
   ps-up-worker-last
   ps-up-other-last
   ps-created-last
   ps-starting-last
   ps-idles-per-minute
   ps-unidles-per-minute
   ps-crashed-last
   ps-timeouts-per-minute
   ps-launch-time-mean
   ps-running-total-last
   ps-running-web-last
   ps-running-worker-last
   ps-running-other-last
   ps-run-requests-per-minute
   ps-runs-per-minute
   ps-returns-per-minute
   ps-stop-requests-per-minute
   ps-stops-per-minute
   ps-converges-per-second
   ps-lost-last
   psmgr-errors-per-minute

   ; packaging
   gitproxy-connections-per-minute
   gitproxy-invalids-per-minute
   gitproxy-errors-per-minute
   gitproxy-successes-per-minute
   gitproxy-mean-metadata-time
   gitproxy-mean-provision-time
   gitproxy-mean-service-time
   codon-launches-per-minute
   codon-receives-per-minute
   codon-exits-per-minute
   codon-cycles-per-minute
   codon-up-last
   codon-busy-last
   codon-compiling-last
   codon-mean-fetch-time
   codon-mean-stow-time
   codon-fetch-errors-per-minute
   codon-stow-errors-per-minute
   codon-mean-service-time
   codon-mean-age
   codon-unhandled-exceptions-per-minute
   slugc-compiles-per-minute
   slugc-failures-per-minute
   slugc-errors-per-minute
   slugc-successes-per-minute
   slugc-aspen-compiles-per-minute
   slugc-bamboo-compiles-per-minute
   slugc-cedar-compiles-per-minute
   slugc-mean-stow-time
   slugc-mean-release-time
   slugc-stow-errors-per-minute
   slugc-release-errors-per-minute
   slugc-mean-compile-time
   codex-errors-per-minute

   ; api
   releases-per-minute
   api-errors-per-minute

   ; data
   shen-errors-per-minute
   ])
