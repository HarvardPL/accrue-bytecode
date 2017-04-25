# time pldiWALA.sh | tee wala$(date +"%Y.%m.%d.%H.%M.%S").txt

if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
scripts may fail if not run from top-level Accrue directory." 
    export ACCRUE_BYTECODE=$PWD
fi

cd $ACCRUE_BYTECODE

max=1

echo ""
echo "TAX"
for i in $(eval echo {1..$max}); do 
#sudo purge
time bin/runAnalysis.sh -cp $PIDGIN/classes/test/       -e test.programs.tax.TaxFreeMain       -n pdg -haf "type(2,1)" -useSingleAllocForImmutableWrappers $1
done

echo ""
echo "UPM"
for i in $(eval echo {1..$max}); do 
#sudo purge
time bin/runAnalysis.sh -cp data/upm.jar                -e com._17od.upm.Driver                -n pdg -haf "type(2,1)" -useSingleAllocForImmutableWrappers -useSingleAllocPerThrowableType $1
done

echo ""
echo "FREECS"
for i in $(eval echo {1..$max}); do 
#sudo purge
time bin/runAnalysis.sh -cp data/freecs.jar             -e freecs.Driver                       -n pdg -haf "type(2,1)" -useSingleAllocForImmutableWrappers -useSingleAllocPerThrowableType -useSingleAllocForPrimitiveArrays $1
done

echo ""
echo "CMS"
for i in $(eval echo {1..$max}); do 
#sudo purge
time bin/runAnalysis.sh -cp data/cms.jar                -e cms.Driver                          -n pdg -haf "type(2,1)" -useSingleAllocForImmutableWrappers -useSingleAllocPerThrowableType -useSingleAllocForPrimitiveArrays $1
done

echo ""
echo "TOMCAT CVE_2011_0013 "
for i in $(eval echo {1..$max}); do 
#sudo purge
time bin/runAnalysis.sh -cp data/tomcat_7_trunk.jar     -e tomcat.Driver_CVE_2011_0013         -n pdg -haf "type(2,1) x CollectionsTypeSensitive(3,2) x StringBuilderFullObjSensitive(2)" -useSingleAllocForGenEx $1
done

echo ""
echo "TOMCAT CVE_2011_2204  "
for i in $(eval echo {1..$max}); do 
#sudo purge
time bin/runAnalysis.sh -cp data/tomcat_7_trunk.jar     -e tomcat.Driver_CVE_2011_2204         -n pdg -haf "type(2,1) x CollectionsTypeSensitive(3,2) x StringBuilderFullObjSensitive(2)" -useSingleAllocForGenEx $1
done

echo ""
echo "TOMCAT CVE_2010_1157 "
for i in $(eval echo {1..$max}); do 
#sudo purge
time bin/runAnalysis.sh -cp data/tomcat_6_trunk.jar     -e tomcat.Driver_CVE_2010_1157         -n pdg -haf "type(2,1) x CollectionsTypeSensitive(3,2) x StringBuilderFullObjSensitive(2)" -useSingleAllocForGenEx $1
done

echo ""
echo "TOMCAT CVE_2014_0033"
for i in $(eval echo {1..$max}); do 
#sudo purge
time bin/runAnalysis.sh -cp data/tomcat_6_trunk.jar     -e tomcat.Driver_CVE_2014_0033         -n pdg -haf "type(2,1) x CollectionsTypeSensitive(3,2) x StringBuilderFullObjSensitive(2)" -useSingleAllocForGenEx $1
done
