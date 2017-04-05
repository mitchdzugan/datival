(ns datival.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [datival.core-test]))

(doo-tests 'datival.core-test)
