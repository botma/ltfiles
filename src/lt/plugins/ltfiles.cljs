(ns lt.plugins.ltfiles
  (:require [lt.object :as object]
            [lt.objs.workspace :as workspace]
            [lt.objs.notifos :as notifos]
            [lt.objs.settings :as settings]
            [lt.objs.bottombar :as bottombar]
            [lt.objs.console :as console]
            [lt.objs.app :as app]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.document :as doc]
            [lt.objs.metrics :as metrics]
            [lt.objs.files :as files]
            [lt.plugins.ltfiles.util :as util]
            [lt.objs.clients.local :as local]
            [lt.objs.tabs :as tabs]
            [lt.objs.opener :as opener]
            [clojure.string :as s]
            [lt.objs.command :as cmd]
            [lt.plugins.ltfiles.selector :as selector]))


;; Prevent common error when introspecting LT objects: "RangeError: Maximum call stack size exceeded"
(set! *print-level* 5)

;; Editor behaviors
;; ================

;; toggle behaviors by changing workspace behavior: is there a more localized way of doing this?
(defn toggle-line-numbers
  "Sets line number first time this is called and toggles on subsequent calls"
  []
  (let [ws-behavior (settings/safe-read (:ws-behaviors @workspace/current-ws) "workspace.behaviors")
        show-line-numbers (some #(= :lt.objs.editor/hide-line-numbers %) (get-in ws-behavior [:+ :editor]))
        line-behavior (if show-line-numbers :lt.objs.editor/line-numbers :lt.objs.editor/hide-line-numbers)
        behavior-string  (->> (get-in ws-behavior [:+ :editor])
                              (remove #{:lt.objs.editor/line-numbers :lt.objs.editor/hide-line-numbers})
                              (cons line-behavior)
                              vec
                              (assoc-in ws-behavior [:+ :editor])
                              pr-str)]
    (swap! workspace/current-ws assoc :ws-behaviors behavior-string)
    (cmd/exec! :behaviors.reload)))

(cmd/command {:command :ltfiles.toggle-line-numbers
              :desc "ltfiles: toggles line numbers"
              :exec toggle-line-numbers})

(defn toggle-strip-whitespace
  "Disables stripping whitespace on first call and toggles on subsequent calls"
  []
  (let [ws-behavior (settings/safe-read (:ws-behaviors @workspace/current-ws) "workspace.behaviors")
        strip-whitespace (some #(= :lt.objs.editor.file/remove-trailing-whitespace %) (get-in ws-behavior [:- :editor.file-backed]))
        add-behavior-fn (if strip-whitespace identity #(cons :lt.objs.editor.file/remove-trailing-whitespace %))
        behavior-string (->> (get-in ws-behavior [:- :editor.file-backed])
                             (remove #{:lt.objs.editor.file/remove-trailing-whitespace})
                             add-behavior-fn
                             vec
                             (assoc-in ws-behavior [:- :editor.file-backed])
                             pr-str)]
    (swap! workspace/current-ws assoc :ws-behaviors behavior-string)
    (cmd/exec! :behaviors.reload)
    ;; timeout needed to override reload msg
    (js/setTimeout
     (fn []
       (notifos/set-msg! (str "Stripping whitespace on save "
                           (if strip-whitespace "enabled." "disabled."))))
     500)))

(cmd/command {:command :ltfiles.toggle-strip-whitespace
              :desc "ltfiles: toggles stripping whitespace on save"
              :exec toggle-strip-whitespace})

;; Console
;; =======

;; open console log so I can search console!
(defn open-console-log-file []
  (cmd/exec! :open-path
             (files/join (files/lt-user-dir "logs") (str "window" (app/window-number) ".log"))))

(cmd/command {:command :ltfiles.open-console-log-file
              :desc "ltfiles: open current console log as an editable/searchable file"
              :exec open-console-log-file})

;; assumes focus is on editor, not console
;; ensures focus remains on current editor
(defn rotate-console []
  (if (bottombar/active? console/console)
    (util/exec-commands [:console.hide :ltfiles.ensure-and-focus-second-tabset :console-tab :console.show :tabset.next])
    ;; assumes console tab is open in second tabset
    ;; NOTE: tabset.next appears buggy which is sometimes causing current tab to close
    (util/exec-commands [:tabset.next :ltfiles.smart-tab-close :toggle-console :tabset.next])))

(cmd/command {:command :ltfiles.rotate-console
              :desc "ltfiles: Rotates open console between bottombar and another tabset"
              :exec rotate-console})


;; Plugins
;; =======

;; must be configured per user
(def plugin-name "ltfiles")

(defn save-plugins []
  (let [personal-plugins-file (files/join (files/lt-user-dir "plugins") plugin-name "plugin.edn")
        deps (->> @app/app
                  :lt.objs.plugins/plugins
                  vals
                  (map (juxt :name :version))
                  sort
                  flatten
                  (apply sorted-map)
                  (#(dissoc % plugin-name)))
        plugin-body (-> (files/open-sync personal-plugins-file)
                        :content
                        (settings/safe-read personal-plugins-file)
                        (assoc :dependencies deps)
                        pr-str)]

    ;; one property and plugin per line for easier editing and diffing
    (files/save personal-plugins-file
                (s/replace plugin-body #"(\"\s*,|\{|\},)" #(str % "\n")))
    (notifos/set-msg! "Plugins saved to " personal-plugins-file)))

(cmd/command {:command :ltfiles.save-plugins
              :desc "ltfiles: Save plugins to :dependencies of personal plugin"
              :exec save-plugins})

(cmd/command {:command :ltfiles.add-plugins-folder
              :desc "ltfiles: Add plugins folder to current workspace"
              :exec (fn []
                      (object/raise workspace/current-ws :add.folder! (files/lt-user-dir "plugins")))})


;; Misc
;; ====

;; assumes only one instance of current folder is open across workspaces
(defn refresh-current-folder []
  (if-let [ws-folder (as-> (util/current-folder) folder
                           (some
                            #(when (= (-> % deref :path) folder) %)
                            (object/instances-by-type :lt.objs.sidebar.workspace/workspace.folder)))]
    (do
      (object/raise ws-folder :refresh!)
      ;; message may print before refresh has actually happened, hook into raise?
      (notifos/set-msg! "Current workspace folder refreshed."))
    (notifos/set-msg! "No workspace folder found to refresh!")))

;; handy for adding/removing files - especially when switching between branches
(cmd/command {:command :ltfiles.refresh-current-workspace-folder
              :desc "ltfiles: Refreshes current workspace folder"
              :exec refresh-current-folder})

;; Faster than mousing around. Only needs to be done once
(cmd/command {:command :ltfiles.connect-to-lt-ui
              :desc "ltfiles: Connect to LT UI via a keystroke"
              :exec local/init})

(cmd/command {:command :ltfiles.print-current-file
              :desc "ltfiles: Print current file path"
              :exec (fn [] (notifos/set-msg! (str "Current path is " (util/current-file))))})


;; copied from opener's ::open-standard-editor
;; TODO: PR so I can just reuse it
(defn open-path [obj path]
  (doc/open path
            (fn [doc]
              (let [type (files/path->type path)
                    ed (pool/create (merge {:doc doc :line-ending (-> @doc :line-ending)} (opener/path->info path)))]
                (metrics/capture! :editor.open {:type (or (:name type) (files/ext path))
                                                :lines (editor/last-line ed)})
                (object/add-tags ed [:editor.file-backed])
                (object/raise obj :open ed)
                (tabs/add! ed)
                (tabs/active! ed)))))

(defn open-current-file []
  (let [current-file (util/current-file)]
    (cmd/exec! :ltfiles.ensure-and-focus-second-tabset)
    (open-path opener/opener current-file)))

(cmd/command {:command :ltfiles.vertical-split-current-file
              :desc "ltfiles: split current file vertically i.e. open in another tabset"
              :exec open-current-file})

(def cmd-selector
  (selector/selector {:items (fn []
                               (->> (:commands @cmd/manager)
                                    vals
                                    (map #(assoc %
                                            :command-desc
                                            (str (:command %) ": " (:desc %))))
                                    (sort-by :command-desc)))
                      :key :command-desc
                      :placeholder "command or description"
                      :transform #(str "<p class='binding'>" %3 "</p>")}))

(cmd/command {:command :ltfiles.commandbar
              :desc "ltfiles: executes a cmd by its id or desc"
              :options cmd-selector
              :exec (fn [cmd]
                      ((:exec cmd)))})

(comment
  (do
    (def win (app/open-window))
    (.on win "focus"
         (fn []
           (prn "SET!")
           (object/raise workspace/current-ws :add.folder! "/Users/me/.gitbeam/technomancy_leiningen"))
           (.on win "focus" (fn []
                              (object/raise app :focus))))
    (.focus win))
  )
