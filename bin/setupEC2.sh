#####
# This file contains commands needed to set up a fresh Amazon EC2 instance
#
# 1. Start a new instance and volume (if you don't have one)
#    Otherwise create a volume and instance in the same location
# 
# 2. Attach the volume with the code
#    On the console Volumes -> Actions -> attach Volume
# 
# 3. login 
# ssh -i ~/.ssh/[KEYFILE_NAME].pem ec2-user@[DNS_NAME]
# #### I aliased "ssh -i [KEYFILE_PATH_AND_NAME].pem" to assh and use:
# assh ec2-user@[DNS_NAME]
#
# 4. Mount the code
# 5. Run this script (need to use "source" for JAVA_HOME to be set properly in this shell)
# sudo mkdir /mnt/experiments; sudo mount /dev/xvdf /mnt/experiments; source /mnt/experiments/accrue-bytecode/bin/setupEC2.sh
#
# 6. Test the setup
# ant clean; ant; ant test

##### 1-time setup (already done if you use my drive)
# scp rt.jar
# change path to JRE in to the path to rt.jar data/wala.properties

sudo yum -y install git ant ant-junit emacs
export JAVA_HOME=/usr/lib/jvm-exports/java-1.7.0-openjdk.x86_64
export ACCRUE_BYTECODE=/mnt/experiments/accrue-bytecode
export PIDGIN=/mnt/experiments/pidgin
cd /mnt/experiments/accrue-bytecode
git pull
ant clean; ant
#ant test