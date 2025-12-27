(ns sci.nrepl.browser-server
  (:require
   [bencode.core :as bencode]
   [cheshire.core :as json]
   [clojure.string :as str]
   [org.httpkit.server :as httpkit])
  (:import
   [java.io PushbackInputStream EOFException BufferedOutputStream]
   [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(defn- coerce-bencode [x]
  (if (bytes? x)
    (String. ^bytes x)
    x))

(defn- remove-nils
  "Remove nil values from a map. Bencode cannot encode nil."
  [m]
  (into {} (remove (fn [[_ v]] (nil? v)) m)))

(defn- read-bencode [in]
  (try (let [msg (bencode/read-bencode in)
             msg (zipmap (map keyword (keys msg))
                         (map coerce-bencode (vals msg)))]
         msg)
       (catch Exception e
         #_(def e e)
         (throw e))))

(defonce ^:private !last-ctx
  (volatile! nil))

(defonce !sessions (atom #{}))

(defn send-response [{:keys [out id session response]
                      :or {out (:out @!last-ctx)}}]
  (let [response (cond-> response
                   id (assoc :id id)
                   session (assoc :session session))
        ;; Remove nil values - bencode cannot encode nil
        response (remove-nils response)]
    (bencode/write-bencode out response)
    (.flush ^java.io.OutputStream out)))

(defn- handle-clone [ctx]
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! !sessions conj id)
    (send-response (assoc ctx
                          :response {"new-session" id "status" ["done"]}))))

(defonce nrepl-channel (atom nil))

;; Atom for Babashka direct eval responses (bypasses nREPL)
(defonce babashka-response-handler (atom nil))

(defn- response-handler [message]
  (let [msg (json/parse-string message true) ;; keywordize keys
        {:keys [id type result error]} msg]
    ;; Check if this is a Babashka direct eval (bb-* prefix)
    (if (and id (str/starts-with? (str id) "bb-"))
      ;; Route to Babashka handler
      (when-let [handler @babashka-response-handler]
        (handler id result error))
      ;; Route to nREPL client
      (let [session (:session @!last-ctx)
            response (cond
                       (= type "result")
                       {"value" result "status" ["done"]}

                       (= type "error")
                       {"err" error "status" ["done"]}

                       :else
                       (-> msg (dissoc :id :session :type) (assoc "status" ["done"])))]
        (send-response {:id id
                        :session session
                        :response response})))))

(defn- websocket-send! [msg]
  (when-let [chan @nrepl-channel]
    ;; Convert :op to :type for Chrome sandbox compatibility
    (let [msg (if (:op msg)
                (-> msg (assoc :type (name (:op msg))) (dissoc :op))
                msg)]
      (httpkit/send! chan (json/generate-string msg)))))

(defn- handle-eval [{:as ctx :keys [msg session id send-fn] :or {send-fn websocket-send!}}]
  (vreset! !last-ctx ctx)
  (let [code (get msg :code)]
    (if (or (str/includes? code "clojure.main/repl-requires")
            (str/includes? code "System/getProperty"))
      (do
        (send-response (assoc ctx :response {"value" "nil"}))
        (send-response (assoc ctx :response {"status" ["done"]})))
      (send-fn {:op :eval
                :code code
                :id id
                :session session}))))

(defn- handle-load-file [ctx]
  (let [msg (get ctx :msg)
        code (get msg :file)
        msg (assoc msg :code code)]
    (handle-eval (assoc ctx :msg msg))))

(defn- handle-complete [{:keys [id session msg send-fn] :or {send-fn websocket-send!}}]
  (send-fn {:op :complete
            :id id
            :session session
            :symbol (get msg :symbol)
            :prefix (get msg :prefix)
            :ns (get msg :ns)}))

(defn- generically-handle-on-server [{:keys [id op session msg send-fn] :or {send-fn websocket-send!}}]
  (send-fn (merge msg
                  {:op op
                   :id id
                   :session session})))

(defn- handle-describe [ctx]
  (vreset! !last-ctx ctx)
  (let [response {"versions" {"sci-nrepl" {"major" "0"
                                           "minor" "0"
                                           "incremental" "1"}}
                  "ops" (zipmap
                         (map
                          name
                          [:eval
                           :info
                           :eldo
                           :lookup
                           :complete
                           :close :clone :load-file
                           :ls-sessions])
                         (repeat {}))
                  "aux" {"cwd" (System/getProperty "user.dir")}
                  :status ["done"]}]
    (send-response (assoc ctx :response response))))

(defn- handle-ls-sessions [ctx]
  "Handle ls-sessions locally - don't forward to browser"
  (send-response (assoc ctx :response {"sessions" (vec @!sessions)
                                       "status" ["done"]})))

(defn- handle-close [ctx]
  "Handle close locally - don't forward to browser"
  (when-let [session (:session ctx)]
    (swap! !sessions disj session))
  (send-response (assoc ctx :response {"status" ["done"]})))

(defn- session-loop [in out {:keys [opts]}]
  (loop []
    (when-let [msg (try
                     (let [msg (read-bencode in)]
                       msg)
                     (catch EOFException _
                       (when-not (:quiet opts)
                         (println "Client closed connection."))))]
      (let [ctx (cond-> {:out out :msg msg}
                  (:send-fn opts)
                  (assoc :send-fn (:send-fn opts)))
            id (get msg :id)
            session (get msg :session)
            ctx (assoc ctx :id id :session session)
            op (keyword (get msg :op))]
        (case op
          :clone (handle-clone ctx)
          :eval (handle-eval ctx)
          :describe (handle-describe ctx)
          :load-file (handle-load-file ctx)
          :complete (handle-complete ctx)
          :ls-sessions (handle-ls-sessions ctx)
          :close (handle-close ctx)
          (generically-handle-on-server (assoc ctx :op op))))
      (recur))))

(defn- listen [^ServerSocket listener {:as opts}]
  (println (str "nREPL server started on port " (:port opts) "..."))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)
        out (BufferedOutputStream. out)]
    (future
      (session-loop in out {:opts opts}))
    (recur listener opts)))

(defonce !socket (atom nil))

(defn start-nrepl-server! [{:keys [port] :as opts}]
  (let [port (or port 1339)
        inet-address (java.net.InetAddress/getByName "localhost")
        socket (new ServerSocket port 0 inet-address)
        _ (reset! !socket socket)]
    (future (listen socket opts))))

(defn stop-nrepl-server! []
  (when-let [socket @!socket]
    (.close ^ServerSocket socket)
    (reset! !socket nil)))

(defn- create-channel [req]
  (httpkit/as-channel req
                      {:on-open (fn [ch]
                                  (reset! nrepl-channel ch))
                       :on-close (fn [_ch _reason] (prn :close))
                       :on-receive
                       (fn [_ch message]
                         (prn :msg message)
                         (response-handler message))}))

(defn- app [{:as req}]
  (when (:websocket? req)
    (case (:uri req)
      "/_nrepl"
      (create-channel req))))

(defonce ^:private !server
  (atom nil))

(defn halt! []
  (when-let [{:keys [port stop-fn]} @!server]
    (stop-fn)
    (println (str "Webserver running on " port ", stopped."))
    (reset! !server nil)))

(defn start-websocket-server! [{:keys [port]}]
  (let [port (or port 1340)]
    (halt!)
    (try
      (reset! !server {:port port :stop-fn (httpkit/run-server #'app {:port port})})
      (println (str "Websocket server started on " port "..."))
      (catch Exception #_java.net.BindException e ;; TODO, add BindException to bb, done for 0.8.3
             (println "Port " port " not available, server not started!")
             (println (.getMessage e))))))

(defn start!
  [{:keys [nrepl-port websocket-port]
    :or {nrepl-port 1339
         websocket-port 1340}}]
  (start-nrepl-server! {:port nrepl-port})
  (start-websocket-server! {:port websocket-port}))
