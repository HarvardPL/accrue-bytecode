#####
# This file contains commands needed to set up a fresh Amazon EC2 instance.
#
# This script should be run on an Amazon EC2 instance. The text below
# describes steps that you need to take to start an Amazon EC2 instance
# before running this script (which performs steps 4 through 6).

#
# 
# 1. From a snapshot, create a volume
#    - Choose a "availability zone" that has low cost for spot instances
# 2. Now that we have a volume, we need an instance. Typically we will use spot instances, i.e., cheap instances that can be preempted.
#    - Use 64-bit Amazon Linux
#    - Choose an instance type. i.e. Andrew has been using c3.8xlarge
#    - Max bid, e.g,. $1. It's a 2nd price auction, so typically won't pay $1.
#    - Choose a subnet that matches the availability zone of the volume.
#    - Launch a spot instance request and wait until it is accepted and an instance is created
# 
# 3. Attach the volume with the code
#    On the console Volumes -> Actions -> attach Volume
# 
# 4. login 
# ssh -i ~/.ssh/[KEYFILE_NAME].pem ec2-user@[DNS_NAME]
# #### I aliased "ssh -i [KEYFILE_PATH_AND_NAME].pem" to assh and use:
# assh ec2-user@[DNS_NAME]
#
# 5. Mount the volume containing the code and run this script to set everything up (need to use "source" for JAVA_HOME to be set properly in this shell)
#    - Just copy the entire next line into your ssh terminal
# sudo mkdir /mnt/experiments; sudo mount /dev/xvdf /mnt/experiments; source /mnt/experiments/accrue-bytecode/bin/setupEC2.sh   
#
# 6. Test the setup (ant test only works for flow-sensitive branch)
# ant clean; ant; ant test

##### 1-time setup (already done if you use my snapshot in step 1.)
# Copy the JDK jar and update wala.properties to point to it
# scp -i ~/.ssh/[KEYFILE_NAME].pem rt.jar ec2-user@[DNS_NAME]:/mnt/experiments/accrue-bytecode/data
# change path to JRE in to the path to rt.jar data/wala.properties

sudo yum -y install git ant ant-junit emacs
export JAVA_HOME=/usr/lib/jvm/java-1.7.0-openjdk.x86_64
export ACCRUE_BYTECODE=/mnt/experiments/accrue-bytecode
export PIDGIN=/mnt/experiments/pidgin
cd /mnt/experiments/accrue-bytecode
git pull
ant clean; ant
#ant test
