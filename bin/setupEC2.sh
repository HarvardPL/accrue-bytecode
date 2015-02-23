% sudo mkdir /mnt/experiments 
% sudo mount /dev/xvdf /mnt/experiments

% 1-time setup
% scp rt.jar
% change path to JRE in to the path to rt.jar data/wala.properties

sudo yum -y install git ant ant-junit emacs
export JAVA_HOME=/usr/lib/jvm/java-1.7.0-openjdk-1.7.0.75.x86_64/