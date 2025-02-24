# Tern

HTTP Artic -> gRPC Antarctic    
K8s CronJob -> gRPC Antarctic   
K8s CronJob -> SpringBatch

## Setup

Make sure you have Docker, Minikube and maven 3.6.3 installed.    

````
minikube start #(using virtualbox)    
minikube addons enable metrics-server    
eval $(minikube docker-env)    
mvn clean install    
docker build -t tern .    
cd deployment    
kubectl apply -f artic.yaml    
kubectl apply -f antarctic.yaml  
kubectl apply -f postgres.yaml  
kubectl apply -f grpcurl-cronjob.yaml    
kubectl apply -f job1-batch-cronjob.yaml    
kubectl apply -f job2-batch-cronjob.yaml    
minikube dashboard
````

## SpringBatch
````
cd target/
java -jar artic-0.0.1-SNAPSHOT.jar job1
````

## K8s CronJob
````
kubectl get jobs --watch
kubectl get pods --selector=job-name=job2-batch-cronjob-29007060
kubectl logs grpc-curl-job-29004415-mb5lb
````