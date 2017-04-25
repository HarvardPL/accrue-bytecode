# bin/runAllAWS.sh 2>&1 | tee wala$(date +"%Y.%m.%d.%H.%M.%S").txt                                                                                                                                                                          
if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
tests may fail if not run from Accrue base directory." 
else
    cd $ACCRUE_BYTECODE
fi

for BM in \
  antlr eclipse fop hsqldb luindex lusearch pmd xalan #bloat chart jython                                                                                                                                                                   
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n collect -useSingleAllocForImmutableWrappers -useSingleAllocForPrimitiveArrays -useSingleAllocPerThrowableType

echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done
