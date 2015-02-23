##### 1-time setup
# scp rt.jar
# change path to JRE in to the path to rt.jar data/wala.properties

##### login
# assh ec2-user@[DNS_NAME]

##### mount drive with code
# sudo mkdir /mnt/experiments; sudo mount /dev/xvdf /mnt/experiments

sudo yum -y install git ant ant-junit emacs
echo Exporting
export JAVA_HOME=/usr/lib/jvm/java-1.7.0-openjdk-1.7.0.75.x86_64/
echo exported

cd /mnt/experiments/accrue-bytecode