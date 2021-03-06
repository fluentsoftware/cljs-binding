(ns cljsbinding
  (:require [cljs.reader :as reader])
  (:use [jayq.core :only [$ attr val change show hide append remove]])
)

(def BindMonitor (atom false))
(def BindDependencies (atom {}))
(def BindFn (atom nil))
(def dynamic-bindings (atom {}))
(def binding-key (atom 0))
(def changing-input (atom nil))

(defn make-js-map
  "makes a javascript map from a clojure one"
  [cljmap]
  (let [out (js-obj)]
    (doall (map #(aset out (name (first %)) (second %)) cljmap))
    out))

(defn translate [data]
  (if (map? data) (make-js-map data) data)
)

(defn visible [elem v]
  (if v (show elem) (hide elem))
)

(defn checked [elem c]
  (.removeAttr elem "checked")
  (if c (attr elem "checked" "checked"))
)

(defn setclass [elem c]
  (.removeClass elem)
  (.addClass elem c)
)

(def bindings {"visible" visible "class" setclass "checked" checked})
(def fnhandlers #{"click" "dblclick"})

(defn in-bindseq? [elem]
  (or
    (> (count (.filter elem "*[bindseq]")) 0)
    (> (count (.parents elem "*[bindseq]")) 0))
)


(defn valuefn [elem fnstr ctx bindingname]
  (if (contains? fnhandlers bindingname)
    (if (in-bindseq? elem) 
      #(.call (js/eval fnstr) nil ctx)    
      #(.call (js/eval fnstr) nil)
    )
  (if (in-bindseq? elem) 
    (translate (.call (js/eval fnstr) nil ctx))    
    (translate (.call (js/eval fnstr) nil))
  ))
)

(defn bind-elem [elem bindingname f]
    (if (contains? bindings bindingname) 
      #((bindings bindingname) elem (f)) 
      #(.call (aget elem bindingname) elem (f))
    ))

(defn bindfn [elem data ctx]
  (let [bindingname (clojure.string/trim (first data)) 
        fname (clojure.string/trim (second data))]
    (bind-elem elem bindingname #(valuefn elem fname ctx bindingname))
  )
)

(defn run-bind-fn [f]
  (reset! BindMonitor true)
  (reset! BindFn f)
  (f)
  (reset! BindMonitor false)
)

(defn bind-jq-elem
  ([elem data ctx] (run-bind-fn (bindfn elem data ctx)))
)

(defn bind [elem ctx]
 (doseq [data (.split (attr elem "bind") ";")] (bind-jq-elem elem (.split data ":") ctx))
)

(defn atom-val [elem atm ctx]
  (let [aval (deref atm)]
    (cond 
      (map? aval) (aval (keyword (attr elem "id")))
      (and (coll? aval) ctx) 
        (let [item (first (filter (fn [x] (= (x :id) (ctx :id))) aval))]
          (if item
            (item (keyword (attr elem "id")))))
      :else aval)
  )
)

(defn update-in-seq [currentseq ctx k v]
  (doall (map (fn [item]
    (if (= (item :id) (ctx :id)) 
      (assoc item k v)
      item
      )
    ) currentseq)))

(defn reset-atom-val [elem atom val ctx]
  (cond 
    (map? @atom) (swap! atom #(assoc % (keyword (attr elem "id")) val))
    (and (coll? @atom) ctx) (swap! atom update-in-seq ctx (keyword (attr elem "id")) val)
    :else (reset! atom val)  
  )  
)

(defn bind-input-atom [elem atm ctx]
  (run-bind-fn #(.call (aget elem "val") elem (atom-val elem atm ctx)))

  (.change elem 
    (fn []
      (reset! changing-input elem)
      (reset-atom-val elem atm (.val elem) ctx)
      (reset! changing-input nil)
      false)
  )
)

(defn bind-checkbox-atom [elem atm ctx]
  (run-bind-fn #(checked elem (atom-val elem atm ctx)))

  (.change elem 
    (fn []
      (reset-atom-val elem atm (.is elem ":checked") ctx)
      false)
    )
)

(defn bind-text-atom [elem atm ctx]
  (run-bind-fn #(.call (aget elem "text") elem (atom-val elem atm ctx))))

(defn bind-elem-to-atom [elem atm ctx]
  (if (or (.is elem "input") (.is elem "textarea") (.is elem "select"))
      (if (= "checkbox" (attr elem "type"))
          (bind-checkbox-atom elem atm ctx)
          (bind-input-atom elem atm ctx)
        )
      (bind-text-atom elem atm ctx)
      ))

(defn bindatom 
  ([elem]
    (let [atm (js/eval (attr elem "bindatom"))]
      (bind-elem-to-atom elem atm nil)))
  ([elem ctx]
    (let [atm (js/eval (attr elem "bindatom"))]
      (bind-elem-to-atom elem atm ctx))))

(defn insert-seq-item [template parent sibling item elem bindfn]
  (if (= (.-length sibling) 1)
    (.after sibling elem)
    (append parent elem))
  (bindfn elem item))

(defn insertseq [templateid sequence parent sibling template bindfn]
  (remove (.children parent (str "[bind-template-id='" templateid "']")))
  (doseq [item sequence] (insert-seq-item template parent sibling item (.attr (.clone template) "bind-template-id" templateid) bindfn))
)

(defn ^:export uuid
  "returns a type 4 random UUID: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"
  []
  (let [r (repeatedly 30 (fn [] (.toString (rand-int 16) 16)))]
    (apply str (concat (take 8 r) ["-"]
                       (take 4 (drop 8 r)) ["-4"]
                       (take 3 (drop 12 r)) ["-"]
                       [(.toString  (bit-or 0x8 (bit-and 0x3 (rand-int 15))) 16)]
                       (take 3 (drop 15 r)) ["-"]
                       (take 12 (drop 18 r))))))

(defn parent-template-id [elem]
  (.attr (.parents ($ elem) "*[bind-template-id]") "bind-template-id"))

(defn bindseq [elem elparent elsibling bindfn]
  (let [atom (js/eval (attr elem "bindseq"))
        templateid (uuid)]    
    (insertseq templateid (deref atom) elparent elsibling elem bindfn)  
    (add-watch atom templateid
          (fn [key a old-val new-val] 
            (let [changing-input-template (if @changing-input (parent-template-id @changing-input) )]
              (if-not (= changing-input-template templateid)
                (insertseq templateid new-val elparent elsibling elem bindfn)))
          )
        )
  )
)

(defn dobind [parent ctx]
  (let [seqs ($ (.find parent "*[bindseq]"))
        seqparents (doall (map #(.parent ($ %)) ($ (.find parent "*[bindseq]"))))
        seqprevsiblings (doall (map #(.prev ($ %)) ($ (.find parent "*[bindseq]"))))
       ]
    (doseq [elem seqs] (remove ($ elem)))
    (doseq [elem (.filter parent "*[bind]")] (bind ($ elem) ctx))
    (doseq [elem (.find parent "*[bind]")] (bind ($ elem) ctx))
    (doseq [elem (.find parent "*[bindatom]")] (bindatom ($ elem) ctx))
    (doseq [[elem parent sibling] (map list seqs seqparents seqprevsiblings)]
      (bindseq ($ elem) parent sibling dobind)
    )
  )
)

(defn ^:export bindall
  ([elem] (dobind elem nil))
  ([elem ctx] (dobind elem ctx))
)


(defn ^:export init []
  (bindall ($ "body") nil)
  )

(defn seq-contains?
  "Determine whether a sequence contains a given item"
  [sequence item]
  (if (empty? sequence)
    false
    (reduce #(or %1 %2) (map #(= %1 item) sequence))))  

(defn add-binding [atom m]
  (assoc m atom (cons @BindFn (m atom)))
)

(defn run-bindings [key a old-val new-val]
  (doseq [f (@BindDependencies a)] (f))
)

(defn next-binding-key [] (swap! binding-key inc))

(defn register-bindingsource [source] 
  (let [bindingkey (str (next-binding-key))]
    (swap! dynamic-bindings #(assoc % bindingkey source))
    bindingkey))

(defn apply-binding [elem source] 
  (if (map? source)
      (doseq [[bindingname f] source] (run-bind-fn (bind-elem elem (name bindingname) f)))    
      (bind-elem-to-atom elem source nil)))

(defn apply-bindingsource [elem bindingkey]
  (doseq [source (@dynamic-bindings bindingkey)] (apply-binding elem source)))

(defn ^:export register [atom]
  (reset! BindMonitor false)
  (swap! BindDependencies (partial add-binding atom))
  (add-watch atom :binding-watch run-bindings)
  (reset! BindMonitor true)
)

(defn ^:export cljsderef [] cljs.core._deref)
(defn ^:export shouldregister [drf] 
(drf BindMonitor))

(defn ^:export boot []
 (js/eval "    
    var derefName = eval('cljsbinding.cljsderef.toString().match(/return.(.*$)\\\\s/m)[1]')
    if (derefName[derefName.length-1] == ';')
      derefName = derefName.substr(0,derefName.length-1)
    var deref = eval(derefName)
    eval(derefName +' = function (a) { if (cljsbinding.shouldregister(deref)) { cljsbinding.register(a) };return deref(a); }')
    cljsbinding.init()")
)

(defn ^:export bind-atom-to-localstorage [name atom]
  (add-watch atom :binding-localstorage-watch
          (fn [key a old-val new-val] 
            (aset js/localStorage name (pr-str new-val))
          )
        )
  (let [storedValue (aget js/localStorage name)]
    (if (not (nil? storedValue))
      (reset! atom (reader/read-string storedValue))
      )
    )  
)                       
