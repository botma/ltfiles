(ns lt.plugins.ltfiles.sacha
  "Experimental extensions to sacha"
  (:require [lt.objs.command :as cmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.editor :as editor]
            [clojure.set :as cset]
            [clojure.string :as s]
            [lt.plugins.ltfiles.util :as util]
            [lt.plugins.sacha.codemirror :as c]))

;; An example outline to practice on
  "
      p0
        convert tabs to spaces *.otl #big
        #cmd - raise node over parent like paredit raise
        upgrade #cm for [p and ]p and setSelections #big
          +also indented system paste
      p9
        zoom,hoisting - requries linked docs or tempfiles #big #cm
        #cmd - open child/sibling/parent above/below
        autocomplete only #tags
  "

(defn desc-node? [node]
  (re-find #"^\s*\+" (:text node)))

(defn parent-node? [curr next]
  (when next
    (and (> (:indent next) (:indent curr))
         (not (desc-node? curr))
         (not (desc-node? next)))))

(defn text->tags [text]
  (map
   #(subs % 1)
   (re-seq #"#\S+" text)))

(defn parent->tag [text]
  (re-find #"\S+" text))

(defn update-tags [tags new-tag]
  (-> (filter #(< (:indent %) (:indent new-tag)) tags)
      (conj (assoc new-tag
              :tag-text (parent->tag (:text new-tag))))))

(defn ->tagged-nodes
  "Returns nodes with :line, :indent, :text and :tags properties.
  Tags are picked up from parents and any words starting with '#'."
  [ed lines]
  (->> lines
       (map #(hash-map :line %
                       :indent (c/line-indent ed %)
                       :text (editor/line ed %)))
       ;; [] needed to include last element
       (partition 2 1 [])
       (reduce
        (fn [accum [curr next]]
          (cond
           (parent-node? curr next)
           (update-in accum [:tags] update-tags curr)

           (not (desc-node? curr))
           (update-in accum [:nodes] conj (assoc curr
                                            :tags (into (map :tag-text (:tags accum))
                                                        (text->tags (:text curr)))))
           :else accum))
        {:tags #{} :nodes []})
       :nodes))

(defn ->tagged-counts
  "For given lines, returns map of tags and how many nodes have that tag."
  [ed lines]
  (->> (->tagged-nodes ed lines)
       (mapcat :tags)
       frequencies))

(cmd/command {:command :ltfiles.tag-counts
              :desc "ltfiles: tag counts in current branch's nodes"
              :exec (fn []
                      (let [ed (pool/last-active)
                            line (.-line (editor/cursor ed))]
                        (prn (->tagged-counts
                         ed
                         (range line (c/safe-next-non-child-line ed line))))))})


;; TODO: make this dynamic per branch
(def config
  {:types {:priority {:names ["p0" "p1" "p2" "p9" "p?" "later"]
                      :default "p?"}
           :duration {:names ["small" "big"]
                      :default "small"}}})

(defn type-counts [type-config nodes]
  (let [default-tag (or (:default type-config) "leftover")]
    (reduce
     (fn [accum node]
       (let [type-tags (cset/intersection (set (:tags node))
                                          (set (:names type-config)))
             ;; assume just one type tag per node for now
             type-tag (if (empty? type-tags) default-tag (first type-tags))]
         #_(prn node type-tag)
         (update-in accum [type-tag] inc)))
     {}
     nodes)))

(defn dynamic-config
  "Types config which calculates certain types based on nodes e.g. unknown type
  which accounts for typeless tags."
  [nodes]
  (let [unaccounted-tags (cset/difference (set (mapcat :tags nodes))
                                          (set (->> config :types vals (mapcat :names))))]
    (assoc-in config [:types :unknown :names] unaccounted-tags)))

(cmd/command {:command :ltfiles.type-counts
              :desc "ltfiles: tag counts of each type for current branch or selection"
              :exec (fn []
                      (let [ed (pool/last-active)
                            line (.-line (editor/cursor ed))
                            lines (if-let [selection (editor/selection-bounds ed)]
                                    (range (get-in selection [:from :line])
                                           (inc (get-in selection [:to :line])))
                                    (range line (c/safe-next-non-child-line ed line)))
                            ;; lines (range 10 20)
                            nodes (->tagged-nodes ed lines)
                            types-config (dynamic-config nodes)]
                        (prn
                         (map
                          #(vector %
                                   (type-counts (get-in types-config [:types %]) nodes))
                          (keys (:types types-config))))))})

;; consider reuse with type-counts
(defn type-map [type-config nodes]
  (let [default-tag (or (:default type-config) "leftover")]
    (reduce
     (fn [accum node]
       (let [type-tags (cset/intersection (set (:tags node))
                                          (set (:names type-config)))
             ;; assume just one type tag per node for now
             type-tag (if (empty? type-tags) default-tag (first type-tags))]
         (update-in accum [type-tag] (fnil conj []) node)))
     {}
     nodes)))


(defn indent-nodes [nodes indent]
  (map
   #(if (:type-tag %)
      (str (apply str (repeat indent " "))
           (:text %))
      ;; TODO: proper indents
      (s/replace-first (:text %) #"^\s*"
                       (apply str (repeat (+ indent 2) " "))))
   nodes))

(cmd/command {:command :ltfiles.switch-view-type
              :desc "ltfiles: switches current branch to view by a chosen type"
              :exec (fn []
                      (let [ed (pool/last-active)
                            line (.-line (editor/cursor ed))
                            lines (range line (c/safe-next-non-child-line ed line))
                            ;; lines (range 10 20)
                            nodes (->tagged-nodes ed lines)
                            type :duration
                            types-config (dynamic-config nodes)
                            view (type-map (get-in types-config [:types type]) nodes)
                            new-nodes (mapcat
                                       (fn [[tag children]]
                                         (into [{:type-tag true :text (name tag)}] children))
                                       view)
                            indented-nodes (indent-nodes new-nodes
                                                         (+ (c/line-indent ed line)
                                                            (editor/option ed "tabSize")))]
                        #_(prn indented-nodes)
                        (util/insert-at-next-line ed (s/join "\n" indented-nodes))))})

(comment

  (let [ed (pool/last-active)]
    (->tagged-counts ed (range 9 19))
    #_(->tagged-nodes ed (range 9 19)))

  (editor/line-handle (pool/last-active) 5))