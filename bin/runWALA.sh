# bin/runWALA.sh 2>&1 | tee wala$(date +"%Y.%m.%d.%H.%M.%S").txt  
# nohup bin/runWALA.sh > wala$(date +"%Y.%m.%d.%H.%M.%S").txt 2> walaerr$(date +"%Y.%m.%d.%H.%M.%S").txt &                                                                                                                                                                  
if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
scripts may fail if not run from top-level Accrue directory." 
    export ACCRUE_BYTECODE=$PWD
fi

cd $ACCRUE_BYTECODE

max=10

for BM in \
  antlr bloat chart eclipse fop hsqldb jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto $1

done

echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done
