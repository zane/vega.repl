(ns zane.vega.repl
  "Vega and Vega-Lite visualization utilities meant to be used interactively at
  the REPL."
  (:import [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.concurrent Worker$State]
           [javafx.event EventHandler]
           [javafx.scene Scene]
           [javafx.scene.web WebEngine WebView]
           [javafx.stage Stage]
           [netscape.javascript JSObject])
  (:require [clojure.java.io :as io]
            [clojure.reflect :as reflect]
            [cheshire.core :as cheshire]))

(defonce ^:private
  ^{:doc "Atom. True if JavaFX has been initialized. False otherwise."}
  initialized (atom false))

(defmacro ^:private if-class [class-name then else]
  `(if (try
         (Class/forName ^String ~class-name)
         (catch ClassNotFoundException _#))
     ~then
     ~else))

(defn- start-javafx-thread!
  []
  ;; Try to start the JavaFX Application Thread one of two ways:
  ;; 1. Call `javafx.application.Platform/startup` if it exists
  ;; 2. Initialize a `javafx.embed.swing.JFXPanel` which starts the JavaFX
  ;;    Application Thread as a side-effect.
  (if (some #(= 'startup (:name %))
            (:members (reflect/reflect Platform))) ; does `javafx.application.Platform/startup` exist?
    @(delay (eval `(Platform/startup (fn []))))
    (if-class "javafx.embed.swing.JFXPanel"
      @(delay (eval `(javafx.embed.swing.JFXPanel.)))
      (throw (ex-info "Can't start JavaFX!" {})))))

(defn ensure-initialized!
  []
  (swap! initialized
         (fn [initialized]
           (when-not initialized
             (Platform/setImplicitExit false) ; don't exit if last window is closed
             (start-javafx-thread!)
             true))))

(defn- run-later*
  "Run `f` on the JavaFX Application Thread. Blocks until `f` returns. For more
  information see `javafx.application.Platform/runLater`."
  [f]
  {:pre [(not (Platform/isFxApplicationThread))]}
  (Platform/runLater f))

(defmacro ^:private run-later
  "Evalute `body` on the JavaFX Application Thread at some unspecified time in the
  future. For more information see `javafx.application.Platform/runLater`."
  [& body]
  `(run-later* (fn [] ~@body)))

(defn- run-now*
  "Run `f` on the JavaFX Application Thread. Blocks until `f` returns. For more
  information see `javafx.application.Platform/runLater`."
  [f]
  {:pre [(not (Platform/isFxApplicationThread))]}
  (let [result (promise)]
    (run-later
     (deliver result (try (f) (catch Throwable e e))))
    @result))

(defmacro ^:private run-now
  "Evalute `body` on the JavaFX Application Thread. Blocks until all the
  expressions in `body` has been evaluated. For more information see
  `javafx.application.Platform/runLater`."
  [& body]
  `(run-now* (fn [] ~@body)))

(defn- load-now!
  "Loads HTML `content` into `web-view`. Blocks until the loading has completed."
  [^WebView web-view content]
  (let [web-view-promise (promise)]
    (run-later
     (let [engine (.getEngine web-view)
           state-property (.stateProperty (.getLoadWorker engine))]
       (.addListener state-property
                     (reify ChangeListener
                       (changed [this ob old new]
                         ;; wait until the page has loaded
                         (when (= new Worker$State/SUCCEEDED)
                           (.removeListener state-property this)
                           (deliver web-view-promise web-view)))))
       (.loadContent engine content)))
    @web-view-promise))

(defn- auto-resize!
  "Configures `stage` such that if the dimensions of the window inside the web
  engine change then the scene will automatically resize to the same
  dimensions.

  Must be called from the JavaFX Application Thread."
  [^Stage stage ^WebView web-view]
  {:pre [(Platform/isFxApplicationThread)]}
  (let [engine (.getEngine web-view)
        ^JSObject window (.executeScript engine "window")
        resize (fn [width height]
                 (.setPrefSize web-view width height)
                 (.sizeToScene stage)
                 (.centerOnScreen stage))]
    ;; This does't appear to actually be called, but still needs to be set on
    ;; `engine`. Otherwise `resize` gets garbage collected(!) even though
    ;; references to it exist inside the JavaScript environment.
    (.setOnResized engine (reify EventHandler
                            (handle [this event]
                              (println "resizing!" resize))))
    ;; Called in `embed.js`.
    (.setMember window "resize" resize)))

(defn- load-vega!
  "Loads Vega or Vega-Lite EDN into the web view. Assumes that vega-embed is
  available on the page."
  [^WebEngine engine spec]
  (.executeScript engine (slurp (io/resource "embed.js")))
  (.executeScript engine (str "doEmbed(" (cheshire/generate-string spec) ");")))

(def ^:dynamic
  ^{:doc "Initial visualization window width."}
  *initial-width* 100)

(def ^:dynamic
  ^{:doc "Initial visualization window width."}
  *initial-height* 100)

(def ^:dynamic
  ^{:doc "*always-on-top* controls whether visualizations should float on top of
   other windows."}
  *always-on-top* true)

(defn vega
  "Renders the provided Vega-Lite spec into a new JavaFX `WebView` window."
  [spec]
  (ensure-initialized!)
  (let [content (slurp (io/resource "index.html"))
        ^WebView web-view (doto (run-now (WebView.))
                            (load-now! content))
        ^WebEngine engine (.getEngine web-view)
        ^Stage stage (run-now (doto (Stage.)
                                (.setScene (Scene. web-view))
                                (.setWidth *initial-width*)
                                (.setHeight *initial-height*)
                                (.setAlwaysOnTop *always-on-top*)))]
    (run-later
     (auto-resize! stage web-view)
     (load-vega! engine spec)
     (.show stage))))
