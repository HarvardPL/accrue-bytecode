for BM in \
  antlr bloat chart eclipse fop hsqldb jython luindex lusearch pmd xalan
do \

echo processing ${BM}...

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -useSingleAllocForImmutableWrappers -useSingleAllocForPrimitiveArrays -useSingleAllocPerThrowableType

done
