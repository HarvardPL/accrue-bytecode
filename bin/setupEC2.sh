#####
# This file contains commands needed to set up a fresh Amazon EC2 instance
#
# The drive with the code is in Oregon region us-west-2a. Your instance should be here too.
# 
# 1. login 
# ssh -i ~/.ssh/[KEYFILE_NAME].pem ec2-user@[DNS_NAME]
# #### I aliased "ssh -i ~/.ssh/[KEYFILE_NAME].pem" to assh and use:
# assh ec2-user@[DNS_NAME]
#
# 2. Mount the code
# sudo mkdir /mnt/experiments; sudo mount /dev/xvdf /mnt/experiments
#
# 3. Run this script (need to use "source" for JAVA_HOME to be set properly in this shell)
# source /mnt/experiments/accrue-bytecode/bin/setupEC2.sh
#
# 4. Test the setup
# ant clean; ant; ant test

##### 1-time setup (already done if you use my drive)
# scp rt.jar
# change path to JRE in to the path to rt.jar data/wala.properties

##### login
# assh ec2-user@[DNS_NAME]

##### mount drive with code
# sudo mkdir /mnt/experiments; sudo mount /dev/xvdf /mnt/experiments

sudo yum -y install git ant ant-junit emacs
export JAVA_HOME=/usr/lib/jvm/java-1.7.0-openjdk-1.7.0.75.x86_64/
cd /mnt/experiments/accrue-bytecode
git pull
