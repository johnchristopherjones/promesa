;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;;
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns promesa.impl
  "Implementation of promise protocols."
  (:require [promesa.protocols :as pt]
            [promesa.util :as pu]
            [promesa.exec :as exec])
  #?(:clj (:import java.util.concurrent.CompletableFuture
                   java.util.concurrent.CompletionStage
                   java.util.concurrent.TimeoutException
                   java.util.concurrent.ExecutionException
                   java.util.concurrent.CompletionException
                   java.util.concurrent.Executor
                   java.util.function.Function
                   java.util.function.Supplier)))

;; --- Global Constants

#?(:cljs (def ^:dynamic *default-promise* js/Promise))

;; --- Promise Impl

(defn empty-deferred
  []
  #?(:clj (CompletableFuture.)
     :cljs
     (let [state #js {}
           obj (new *default-promise*
                    (fn [resolve reject]
                      (set! (.-resolve state) resolve)
                      (set! (.-reject state) reject)))]
       (specify! obj
         pt/ICompletable
         (-resolve! [_ v]
           (.resolve state v))
         (-reject! [_ v]
           (.reject state v))))))

;; (defn factory->promise
;;   [f]
;;   #?(:cljs (new *default-promise* f)
;;      :clj  (let [p (CompletableFuture.)
;;                  reject #(.completeExceptionally p %)
;;                  resolve #(.complete p %)]
;;              (try
;;                (f resolve reject)
;;                (catch Throwable e
;;                  (reject e)))
;;              p)))

#?(:cljs
   (defn extend-promise!
     [t]
     (extend-type t
       pt/IPromiseFactory
       (-promise [p] p)

       pt/IPromise
       (-map [it f e] (.then it #(f %)))
       (-bind [it f e] (.then it #(f %)))
       (-handle [it f e] (.then it #(f % nil) #(f nil %)))
       (-catch [it f] (.catch it #(f %))))))

#?(:cljs
   (extend-promise! js/Promise))

#?(:cljs
   (extend-type default
     pt/IPromise
     (-map [it f e] (pt/-map (pt/-promise it) f e))
     (-bind [it f e] (pt/-bind (pt/-promise it) f e))
     (-handle [it f e] (pt/-handle (pt/-promise it) f e))
     (-catch [it f] (pt/-catch (pt/-promise it) f))))

#?(:clj
   (extend-protocol pt/IPromise
     CompletionStage
     (-map [it f executor]
       (.thenApplyAsync ^CompletionStage it
                        ^Function (pu/->FunctionWrapper f)
                        ^Executor (exec/resolve-executor executor)))

     (-bind [it f executor]
       (.thenComposeAsync ^CompletionStage it
                          ^Function (pu/->FunctionWrapper f)
                          ^Executor (exec/resolve-executor executor)))

     (-handle [it f executor]
       (.handleAsync ^CompletionStage it
                     ^BiFunction (pu/->BiFunctionWrapper f)
                     ^Executor (exec/resolve-executor executor)))

     (-catch [it f]
       (letfn [(handler [e]
                 (if (instance? CompletionException e)
                   (f (.getCause ^Exception e))
                   (f e)))]
         (.exceptionally ^CompletionStage it
                         ^Function (pu/->FunctionWrapper handler))))

     Object
     (-map [it f e] (pt/-map (pt/-promise it) f e))
     (-bind [it f e] (pt/-bind (pt/-promise it) f e))
     (-handle [it f e] (pt/-handle (pt/-promise it) f e))
     (-catch [it f] (pt/-catch (pt/-promise it) f))

     nil
     (-map [it f e] (pt/-map (pt/-promise it) f e))
     (-bind [it f e] (pt/-bind (pt/-promise it) f e))
     (-handle [it f e] (pt/-handle (pt/-promise it) f e))
     (-catch [it f] (pt/-catch (pt/-promise it) f))))

#?(:clj
   (extend-type CompletableFuture
     pt/ICancellable
     (-cancel! [it]
       (.cancel it true))
     (-cancelled? [it]
       (.isCancelled it))

     pt/ICompletable
     (-resolve! [f v] (.complete f v))
     (-reject! [f v] (.completeExceptionally f v))

     pt/IState
     (-extract [it]
       (try
         (.getNow it nil)
         (catch ExecutionException e
           (.getCause e))
         (catch CompletionException e
           (.getCause e))))

     (-resolved? [it]
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (.isDone it)))

     (-rejected? [it]
       (.isCompletedExceptionally it))

     (-pending? [it]
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (not (.isDone it))))))

;; --- Promise Factory Impl

(defn resolved
  [v]
  #?(:cljs (.resolve *default-promise* v)
     :clj (CompletableFuture/completedFuture v)))

(defn rejected
  [v]
  #?(:cljs (.reject *default-promise* v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally p v)
            p)))

#?(:clj
   (extend-protocol pt/IPromiseFactory
     CompletionStage
     (-promise [cs] cs)

     Throwable
     (-promise [e]
       (rejected e))

     Object
     (-promise [v]
       (resolved v))

     nil
     (-promise [v]
       (resolved v)))

   :cljs
   (extend-protocol pt/IPromiseFactory
     js/Error
     (-promise [e]
       (rejected e))

     default
     (-promise [v]
       (resolved v))))

;; --- Pretty printing

(defn promise->str
  [p]
  "#<Promise[~]>")

#?(:clj
   (defmethod print-method java.util.concurrent.CompletionStage
     [p ^java.io.Writer writer]
     (.write writer ^String (promise->str p))))

#?(:cljs
   (extend-type js/Promise
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer (promise->str p)))))
