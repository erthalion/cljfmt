(ns cljfmt.core
  #?@(:clj
      [(:refer-clojure :exclude [reader-conditional?])
       (:require
         [clojure.java.io :as io]
         [clojure.zip :as zip]
         [rewrite-clj.node :as n]
         [rewrite-clj.parser :as p]
         [rewrite-clj.zip :as z
          :refer [append-space edn skip whitespace-or-comment?]])
        (:import java.util.regex.Pattern)]
      :cljs
       [(:require
         [cljs.reader :as reader]
         [clojure.zip :as zip]
         [clojure.string :as str]
         [rewrite-clj.node :as n]
         [rewrite-clj.parser :as p]
         [rewrite-clj.zip :as z]
         [rewrite-clj.zip.base :as zb :refer [edn]]
         [rewrite-clj.zip.whitespace :as zw
          :refer [append-space skip whitespace-or-comment?]])
        (:require-macros [cljfmt.core :refer [read-resource]])]))

#?(:clj (def read-resource* (comp read-string slurp io/resource)))
#?(:clj (defmacro read-resource [path] `'~(read-resource* path)))

(def zwhitespace?
  #?(:clj z/whitespace? :cljs zw/whitespace?))

(def zlinebreak?
  #?(:clj z/linebreak? :cljs zw/linebreak?))

(def includes?
  #?(:clj  (fn [^String a ^String b] (.contains a b))
     :cljs str/includes?))

(defn- edit-all [zloc p? f]
  (loop [zloc (if (p? zloc) (f zloc) zloc)]
    (if-let [zloc (z/find-next zloc zip/next p?)]
      (recur (f zloc))
      zloc)))

(defn- transform [form zf & args]
  (z/root (apply zf (edn form) args)))

(defn- surrounding? [zloc p?]
  (and (p? zloc) (or (nil? (zip/left zloc))
                     (nil? (skip zip/right p? zloc)))))

(defn- top? [zloc]
  (and zloc (not= (z/node zloc) (z/root zloc))))

(defn- surrounding-whitespace? [zloc]
  (and (top? (z/up zloc))
       (surrounding? zloc zwhitespace?)))

(defn remove-surrounding-whitespace [form]
  (transform form edit-all surrounding-whitespace? zip/remove))

(defn- element? [zloc]
  (and zloc (not (whitespace-or-comment? zloc))))

(defn- reader-macro? [zloc]
  (and zloc (= (n/tag (z/node zloc)) :reader-macro)))

(defn- missing-whitespace? [zloc]
  (and (element? zloc)
       (not (reader-macro? (zip/up zloc)))
       (element? (zip/right zloc))))

(defn insert-missing-whitespace [form]
  (transform form edit-all missing-whitespace? append-space))

(defn- whitespace? [zloc]
  (= (z/tag zloc) :whitespace))

(defn- comma? [zloc]
  (= (z/tag zloc) :comma))

(defn- comment? [zloc]
  (some-> zloc z/node n/comment?))

(defn- line-break? [zloc]
  (or (zlinebreak? zloc) (comment? zloc)))

(defn- skip-whitespace [zloc]
  (skip zip/next whitespace? zloc))

(defn- skip-comma [zloc]
  (skip zip/next comma? zloc))

(defn- count-newlines [zloc]
  (loop [zloc zloc, newlines 0]
    (if (zlinebreak? zloc)
      (recur (-> zloc zip/right skip-whitespace)
             (-> zloc z/string count (+ newlines)))
      newlines)))

(defn- consecutive-blank-line? [zloc]
  (> (count-newlines zloc) 2))

(defn- remove-whitespace-and-newlines [zloc]
  (if (zwhitespace? zloc)
    (recur (zip/remove zloc))
    zloc))

(defn- replace-consecutive-blank-lines [zloc]
  (-> zloc (zip/replace (n/newlines 2)) zip/next remove-whitespace-and-newlines))

(defn remove-consecutive-blank-lines [form]
  (transform form edit-all consecutive-blank-line? replace-consecutive-blank-lines))

(defn- indentation? [zloc]
  (and (line-break? (zip/prev zloc)) (whitespace? zloc)))

(defn- comment-next? [zloc]
  (-> zloc zip/next skip-whitespace comment?))

(defn- line-break-next? [zloc]
  (-> zloc zip/next skip-whitespace line-break?))

(defn- should-indent? [zloc]
  (and (line-break? zloc) (not (line-break-next? zloc))))

(defn- should-unindent? [zloc]
  (and (indentation? zloc) (not (comment-next? zloc))))

(defn unindent [form]
  (transform form edit-all should-unindent? zip/remove))

(def ^:private start-element
  {:meta "^", :meta* "#^", :vector "[",       :map "{"
   :list "(", :eval "#=",  :uneval "#_",      :fn "#("
   :set "#{", :deref "@",  :reader-macro "#", :unquote "~"
   :var "#'", :quote "'",  :syntax-quote "`", :unquote-splicing "~@"})

(defn- prior-line-string [zloc]
  (loop [zloc     zloc
         worklist '()]
    (if-let [p (zip/left zloc)]
      (let [s            (str (n/string (z/node p)))
            new-worklist (cons s worklist)]
        (if-not (includes? s "\n")
          (recur p new-worklist)
          (apply str new-worklist)))
      (if-let [p (zip/up zloc)]
        ;; newline cannot be introduced by start-element
        (recur p (cons (start-element (n/tag (z/node p))) worklist))
        (apply str worklist)))))

(defn- last-line-in-string [^String s]
  (subs s (inc (.lastIndexOf s "\n"))))

(defn- margin [zloc]
  (-> zloc prior-line-string last-line-in-string count))

(defn- whitespace [width]
  (n/whitespace-node (apply str (repeat width " "))))

(defn- coll-indent [zloc]
  (-> zloc zip/leftmost margin))

(defn- index-of [zloc]
  (->> (iterate z/left zloc)
       (take-while identity)
       (count)
       (dec)))

(defn- list-indent [zloc]
  (if (> (index-of zloc) 1)
    (-> zloc zip/leftmost z/right margin)
    (coll-indent zloc)))

(def indent-size 2)

(defn- indent-width [zloc]
  (case (z/tag zloc)
    :list indent-size
    :fn   (inc indent-size)))

(defn- remove-namespace [x]
  (if (symbol? x) (symbol (name x)) x))

(defn pattern? [v]
  (instance? #?(:clj Pattern :cljs js/RegExp) v))

(defn- indent-matches? [key sym]
  (cond
    (symbol? key) (= key sym)
    (pattern? key) (re-find key (str sym))))

(defn- token? [zloc]
  (= (z/tag zloc) :token))

(defn- token-value [zloc]
  (and (token? zloc) (z/sexpr zloc)))

(defn- reader-conditional? [zloc]
  (and (reader-macro? zloc) (#{"?" "?@"} (-> zloc z/down token-value str))))

(defn- form-symbol [zloc]
  (-> zloc z/leftmost token-value remove-namespace))

(defn- index-matches-top-argument? [zloc depth idx]
  (and (> depth 0)
       (= idx (index-of (nth (iterate z/up zloc) (dec depth))))))

(defn- inner-indent [zloc key depth idx]
  (let [top (nth (iterate z/up zloc) depth)]
    (if (and (indent-matches? key (form-symbol top))
             (or (nil? idx) (index-matches-top-argument? zloc depth idx)))
      (let [zup (z/up zloc)]
        (+ (margin zup) (indent-width zup))))))

(defn- nth-form [zloc n]
  (reduce (fn [z f] (if z (f z)))
          (z/leftmost zloc)
          (repeat n z/right)))

(defn- first-form-in-line? [zloc]
  (if-let [zloc (zip/left zloc)]
    (if (whitespace? zloc)
      (recur zloc)
      (or (zlinebreak? zloc) (comment? zloc)))
    true))

(defn- block-indent [zloc key idx]
  (if (indent-matches? key (form-symbol zloc))
    (if (and (some-> zloc (nth-form (inc idx)) first-form-in-line?)
             (> (index-of zloc) idx))
      (inner-indent zloc key 0 nil)
      (list-indent zloc))))

(def default-indents
  (merge (read-resource "cljfmt/indents/clojure.clj")
         (read-resource "cljfmt/indents/compojure.clj")
         (read-resource "cljfmt/indents/fuzzy.clj")))

(defmulti ^:private indenter-fn
  (fn [sym [type & args]] type))

(defmethod indenter-fn :inner [sym [_ depth idx]]
  (fn [zloc] (inner-indent zloc sym depth idx)))

(defmethod indenter-fn :block [sym [_ idx]]
  (fn [zloc] (block-indent zloc sym idx)))

(defn- make-indenter [[key opts]]
  (apply some-fn (map (partial indenter-fn key) opts)))

(defn- indent-order [[key _]]
  (cond
    (symbol? key) (str 0 key)
    (pattern? key) (str 1 key)))

(defn- custom-indent [zloc indents]
  (if (empty? indents)
    (list-indent zloc)
    (let [indenter (->> (sort-by indent-order indents)
                        (map make-indenter)
                        (apply some-fn))]
      (or (indenter zloc)
          (list-indent zloc)))))

(defn- indent-amount [zloc indents]
  (let [tag (-> zloc z/up z/tag)
        gp  (-> zloc z/up z/up)]
    (cond
      (reader-conditional? gp) (coll-indent zloc)
      (#{:list :fn} tag)       (custom-indent zloc indents)
      (= :meta tag)            (indent-amount (z/up zloc) indents)
      :else                    (coll-indent zloc))))

(defn- indent-line [zloc indents]
  (let [width (indent-amount zloc indents)]
    (if (> width 0)
      (zip/insert-right zloc (whitespace width))
      zloc)))

(defn indent
  ([form]
   (indent form default-indents))
  ([form indents]
   (transform form edit-all should-indent? #(indent-line % indents))))

(defn reindent
  ([form]
   (indent (unindent form)))
  ([form indents]
   (indent (unindent form) indents)))

(defn root? [zloc]
  (nil? (zip/up zloc)))

(defn final? [zloc]
  (and (nil? (zip/right zloc)) (root? (zip/up zloc))))

(defn- trailing-whitespace? [zloc]
  (and (whitespace? zloc)
       (or (zlinebreak? (zip/right zloc)) (final? zloc))))

(defn remove-trailing-whitespace [form]
  (transform form edit-all trailing-whitespace? zip/remove))

(defn- append-newline-if-absent [zloc]
  (if (or (-> zloc zip/right skip-whitespace skip-comma line-break?)
          (z/rightmost? zloc))
      zloc
      (zip/insert-right zloc (n/newlines 1))))

(defn- map-odd-seq
  "Applies f to all oddly-indexed nodes."
  [f zloc]
  (loop [loc (z/down zloc)
         parent zloc]
    (if-not (and loc (z/node loc))
      parent
      (if-let [v (f loc)]
        (recur (z/right (z/right v)) (z/up v))
        (recur (z/right (z/right loc)) parent)))))

(defn- map-even-seq
  "Applies f to all evenly-indexed nodes."
  [f zloc]
  (loop [loc   (z/right (z/down zloc))
         parent zloc]
    (if-not (and loc (z/node loc))
      parent
      (if-let [v (f loc)]
        (recur (z/right (z/right v)) (z/up v))
        (recur (z/right (z/right loc)) parent)))))

(defn- add-map-newlines [zloc]
  (map-even-seq #(cond-> % (complement z/rightmost?)
                         append-newline-if-absent) zloc))

(defn- add-binding-newlines [zloc]
  (map-even-seq append-newline-if-absent zloc))

(defn- update-in-path [[node path :as loc] k f]
  (let [v (get path k)]
    (if (seq v)
      (with-meta
        [node (assoc path k (f v) :changed? true)]
        (meta loc))
      loc)))

(defn- remove-right
  [loc]
  (update-in-path loc :r next))

(defn- *remove-right-while
  [zloc p?]
  (loop [zloc zloc]
    (if-let [rloc (zip/right zloc)]
      (if (p? rloc)
        (recur (remove-right zloc))
        zloc)
      zloc)))

(defn- align-seq-value [zloc max-length]
  (let [key-length (-> zloc z/sexpr str count)
        width      (- max-length key-length)
        zloc       (*remove-right-while zloc zwhitespace?)]
    (zip/insert-right zloc (whitespace (inc width)))))

(defn- align-map [zloc]
  (let [key-list       (-> zloc z/sexpr keys)
        max-key-length (apply max (map #(-> % str count) key-list))]
    (map-odd-seq #(align-seq-value % max-key-length) zloc)))

(defn- align-binding [zloc]
  (let [vec-sexpr    (z/sexpr zloc)
        odd-elements (take-nth 2 vec-sexpr)
        max-length   (apply max (map #(-> % str count) odd-elements))]
    (map-odd-seq #(align-seq-value % max-length) zloc)))

(defn- align-elements [zloc]
  (if (z/map? zloc)
      (-> zloc align-map add-map-newlines)
      (-> zloc align-binding add-binding-newlines)))

(def ^:private binding-keywords
  #{"doseq" "let" "loop" "binding" "with-open" "go-loop" "if-let" "when-some"
    "if-some" "for" "with-local-vars" "with-redefs"})

(defn- binding? [zloc]
  (and (z/vector? zloc)
       (-> zloc z/sexpr count even?)
       (->> zloc
            z/left
            z/string
            (contains? binding-keywords))))

(defn- align-binding? [zloc]
  (and (binding? zloc)
       (-> zloc z/sexpr count (> 2))))

(defn- empty-seq? [zloc]
  (if (z/map? zloc)
      (-> zloc z/sexpr empty?)
      false))

(defn- align-map? [zloc]
  (and (z/map? zloc)
       (not (empty-seq? zloc))))

(defn- align-elements? [zloc]
  (or (align-binding? zloc)
      (align-map? zloc)))

(defn align-collection-elements [form]
  (transform form edit-all align-elements? align-elements))


(defn reformat-form [form & [{:as opts}]]
  (-> form
      (cond-> (:remove-consecutive-blank-lines? opts true)
        remove-consecutive-blank-lines)
      (cond-> (:remove-surrounding-whitespace? opts true)
        remove-surrounding-whitespace)
      (cond-> (:insert-missing-whitespace? opts true)
        insert-missing-whitespace)
      (cond-> (:align-associative? opts true)
        align-collection-elements)
      (cond-> (:indentation? opts true)
        (reindent (:indents opts default-indents)))
      (cond-> (:remove-trailing-whitespace? opts true)
        remove-trailing-whitespace)))

(defn reformat-string [form-string & [options]]
  (-> (p/parse-string-all form-string)
      (reformat-form options)
      (n/string)))
