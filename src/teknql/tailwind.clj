(ns teknql.tailwind
  (:require [jsonista.core :as j]
            [cuerdas.core :as str]
            [babashka.process :as proc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def default-tailwind-config
  "Default tailwind config"
  {:future   {}
   :purge    []
   :mode     "jit"
   :theme    {:extend {}}
   :variants {}
   :plugins  []})

(defonce ^{:doc "Static state atom associating shadow-cljs build IDs to their respective state."}
  projects
  (atom {}))

(defn- ->json
  "Encode the provided value to JSON"
  [val]
  (j/write-value-as-string
    val
    (j/object-mapper {:encode-key-fn (comp str/camel name)})))

(defn ->export-json
  "Return the provided val as an string with a `module.exports`.

  Used for generating the various *.config.js files that the Node ecosystem loves"
  [val]
  (str "module.exports = " (->json val) ";"))

(defn- log
  "Log the provided `strs` to stderr with a prefix determined by the build ID
  of the passed in `build-cfg`."
  [build-cfg & strs]
  (binding [*out* *err*]
    (println (apply str "[" (:build-id build-cfg) "] " strs))))

(defn- cfg-get
  "Behaves identical to `get` but logs the default value back to the user."
  [config key default]
  (or (get config key)
      (do (log config "Using default value for " key ".")
          default)))

(defn create-tmp-tailwind-project!
  "Create a temporary tailwind project with the necessary assets to build the project using the JIT.

  Return the path to the temporary directory."
  [postcss-cfg tailwind-cfg]
  (let [tmp-dir              (-> (Files/createTempDirectory "tailwind" (make-array FileAttribute 0))
                                 (.toFile)
                                 (.getAbsolutePath))
        tmp-css-path         (str tmp-dir "/" "tailwind.css")
        tmp-tw-cfg-path      (str tmp-dir "/" "tailwind.config.js")
        tmp-postcss-cfg-path (str tmp-dir "/" "postcss.config.js")]
    (spit tmp-css-path "@tailwind base;\n@tailwind components;\n@tailwind utilities;")
    (spit tmp-tw-cfg-path (->export-json tailwind-cfg))
    (spit tmp-postcss-cfg-path (-> postcss-cfg
                                   (assoc-in [:plugins :tailwindcss :config] tmp-tw-cfg-path)
                                   (->export-json)))
    tmp-dir))

(defn start-watch!
  "Start the tailwind JIT"
  {:shadow.build/stage :configure}
  [build-state]
  (let [config      (:shadow.build/config build-state)
        build-id    (:build-id config)
        http-root   (-> config :devtools :http-root)
        output-path (cfg-get config :tailwind/output "resources/public/css/site.css")
        tw-cfg      (merge default-tailwind-config
                           {:purge [(str http-root "/**/*.js")
                                    (str http-root "/**/*.html")]}
                           (cfg-get config :tailwind/config nil))
        post-cfg    (merge {:plugins {:tailwindcss {}}}
                             (cfg-get config :postcss/config nil))
        project-def (get @projects build-id)
        tmp-dir     (create-tmp-tailwind-project!
                      post-cfg
                      tw-cfg)]
    (when-not (and (= (:tailwind/config project-def)
                      tw-cfg)
                   (= (:postcss/config project-def)
                      post-cfg)
                   (= (:tailwind/output project-def)
                      output-path))
      (if-some [existing-proc (:process project-def)]
        (do (log config "Restarting postcss process.")
            (proc/destroy existing-proc))
        (log config "Starting postcss process."))
      (swap! projects
             assoc
             build-id
             {:tailwind/config tw-cfg
              :tailwind/output output-path
              :postcss/config  post-cfg
              :process
              (proc/process
                ["./node_modules/.bin/postcss"
                 (str tmp-dir "/tailwind.css")
                 "--config"
                 tmp-dir
                 "--watch"
                 "-o"
                 output-path]
                {:extra-env {"NODE_ENV"      "development"
                             "TAILWIND_MODE" "watch"}
                 :err       :inherit
                 :out       :inheirt})}))
    build-state))

(defn compile-release!
  "Compile the release build of the CSS generated by tailwind."
  {:shadow.build/stage :flush}
  [build-state]
  (let [config      (:shadow.build/config build-state)
        output-path (cfg-get config :tailwind/output "resources/public/css/site.css")
        http-root   (-> config :devtools :http-root)
        tmp-dir     (create-tmp-tailwind-project!
                      (merge {:plugins {:tailwindcss  {}
                                        :autoprefixer {}
                                        :cssnano      {:preset "default"}}}
                             (cfg-get config :postcss/config nil))
                      (merge default-tailwind-config
                             {:purge [(str http-root "/**/*.js")
                                      (str http-root "/**/*.html")]}
                             (cfg-get config :tailwind/config nil)))]
    (log config "Generating tailwind output")
    (-> (proc/process
          ["./node_modules/.bin/postcss"
           (str tmp-dir "/tailwind.css")
           "--config"
           tmp-dir
           "-o"
           output-path]
          {:extra-env {"NODE_ENV"      "production"
                       "TAILWIND_MODE" "build"}})
        deref)
    build-state))
