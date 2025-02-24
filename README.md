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

## SrpingBatch
````
cd target/
java -jar artic-0.0.1-SNAPSHOT.jar job1
````
## Grpcurl
````
minikube service antarctic --url
````
😿  service default/antarctic has no node port    
http://127.0.0.1:51377

````
grpcurl -plaintext 127.0.0.1:51377 list
````
grpc.health.v1.Health    
grpc.reflection.v1alpha.ServerReflection    
tern.grpc.TernService    

````
grpcurl -plaintext -d '{"text":"some-text"}' 127.0.0.1:51377 tern.grpc.TernService/SaveMessage
````
{
"id": "67d1d96a-3a05-404e-873b-9db2afaa6686"
}


## K8s CronJob
### Watch jobs
````
kubectl get jobs --watch
````
### Get the pod running this job
````
kubectl get pods --selector=job-name=grpc-curl-job-29004415
````
NAME                           READY   STATUS      RESTARTS   AGE    
grpc-curl-job-29004415-mb5lb   0/1     Completed   0          48s    

### See logs of this pod
````
kubectl logs grpc-curl-job-29004415-mb5lb
````
{
"id": "91fcb2d7-0284-4fac-bd45-f494e5eecd8f"
}

### See results
Get Artic service url using 

````
minikube service artic --url
````
And then curl the service
````
curl http://127.0.0.1:51308
````
