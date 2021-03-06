(ns com.walmartlabs.lacinia.resolve
  "Complex results for field resolver functions."
  (:import (java.util.concurrent Executor)))

(def ^{:dynamic true
       :added "0.20.0"} *callback-executor*
  "If non-nil, then specifies a java.util.concurrent.Executor (typically, a thread pool of some form) used to invoke callbacks
  when ResolveResultPromises are delivered."
  nil)

(defprotocol ^:no-doc ResolveCommand
  "Used to define special wrappers around resolved values, such as [[with-error]].

  This is not intended for use by applications, as the structure of the selection-context
  is not part of Lacinia's public API."
  (^:no-doc apply-command [this selection-context]
    "Applies changes to the selection context, which is returned.")
  (^:no-doc nested-value [this]
    "Returns the value wrapped by this command, which may be another command or a final result."))

(defn with-error
  "Wraps a value with an error map (or seq of error maps)."
  {:added "0.19.0"}
  [value error]
  (reify
    ResolveCommand

    (apply-command [_ selection-context]
      (update selection-context :errors conj error))

    (nested-value [_] value)))

(defn with-context
  "Wraps a value so that when nested fields (at any depth) are executed, the provided values will be in the context.

   The provided context-map is merged onto the application context."
  {:added "0.19.0"}
  [value context-map]
  (reify
    ResolveCommand

    (apply-command [_ selection-contexct]
      (update-in selection-contexct [:execution-context :context] merge context-map))

    (nested-value [_] value)))

(defprotocol ResolverResult
  "A special type returned from a field resolver that can contain a resolved value
  and/or errors."

    (on-deliver! [this callback]
    "Provides a callback that is invoked immediately after the ResolverResult is realized.
    The callback is passed the ResolverResult's value.

    `on-deliver!` should only be invoked once.
    It invokes the callback in the current thread.
    It returns `this`.

    On a simple ResolverResult (not a ResolverResultPromise), the callback is invoked
    immediately.

    The callback is invoked for side-effects; its result is ignored."))

(defprotocol ResolverResultPromise
  "A specialization of ResolverResult that supports asynchronous delivery of the resolved value and errors."

  (deliver!
    [this value]
    [this value errors]
    "Invoked to realize the ResolverResult, triggering the callback to receive the value and errors.

    The callback is invoked in the current thread, unless [[*thread-pool*]] is non-nil, in which case
    the callback is invoked in a pooled thread.

    The two arguments version is simply a convienience around [[with-error]].

    Returns `this`."))

(defrecord ^:private ResolverResultImpl [resolved-value]

  ResolverResult

  (on-deliver! [this callback]
    (callback resolved-value)
    this))

(defn resolve-as
  "Invoked by field resolvers to wrap a simple return value as a ResolverResult.

  The two-arguments version is a convienience around using [[with-error]].

  This is an immediately realized ResolverResult."
  ([resolved-value]
   (->ResolverResultImpl resolved-value))
  ([resolved-value resolver-errors]
   (->ResolverResultImpl (with-error resolved-value resolver-errors))))

(defn resolve-promise
  "Returns a ResolverResultPromise.

   A value must be resolved and ultimately provided via [[deliver!]]."
  []
  (let [*result (promise)
        *callback (promise)]
    (reify
      ResolverResult

      ;; We could do a bit more locking to avoid a couple of race-condition edge cases, but this is mostly to sanity
      ;; check bad application code that simply gets the contract wrong.
      (on-deliver! [this callback]
        (cond
          ;; If the value arrives before the callback, invoke the callback immediately.
          (realized? *result)
          (callback @*result)

          (realized? *callback)
          (throw (IllegalStateException. "ResolverResultPromise callback may only be set once."))

          :else
          (deliver *callback callback))

        this)

      ResolverResultPromise

      (deliver! [this resolved-value]
        (when (realized? *result)
          (throw (IllegalStateException. "May only realize a ResolverResultPromise once.")))

        (deliver *result resolved-value)

        (when (realized? *callback)
          (let [callback @*callback]
            (if-some [^Executor executor *callback-executor*]
              (.execute executor (bound-fn [] (callback resolved-value)))
              (callback resolved-value))))

        this)

      (deliver! [this resolved-value error]
        (deliver! this (with-error resolved-value error))))))

(defn ^:no-doc combine-results
  "Given a left and a right ResolverResult, returns a new ResolverResult that combines
  the realized values using the provided function."
  [f left-result right-result]
  (let [combined-result (resolve-promise)]
    (on-deliver! left-result
                 (fn receive-left-value [left-value]
                   (on-deliver! right-result
                                (fn receive-right-value [right-value]
                                  (deliver! combined-result (f left-value right-value))))))
    combined-result))
