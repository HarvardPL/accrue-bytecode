# bin/runAllAWS.sh 2>&1 | tee wala$(date +"%Y.%m.%d.%H.%M.%S").txt                                                                                                                                                                          
if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
scripts may fail if not run from top-level Accrue directory." 
    export ACCRUE_BYTECODE=$PWD
fi

cd $ACCRUE_BYTECODE

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
