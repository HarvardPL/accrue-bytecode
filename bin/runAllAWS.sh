for BM in \
  antlr bloat eclipse fop hsqldb luindex lusearch pmd xalan #chart jython
do \

echo processing ${BM}...

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -useSingleAllocForImmutableWrappers -useSingleAllocForPrimitiveArrays -useSingleAllocPerThrowableType

done
