# Tern

HTTP Artic -> gRPC Antarctic    
K8s CronJob (grpcurl) -> gRPC Antarctic   
K8s CronJob (job1) -> SpringBatch -> gRPC Antarctic    
K8s CronJob (job2) -> SpringBatch    

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
kubectl apply -f grpcurl-cronjob.yaml    
kubectl apply -f job1-batch-cronjob.yaml    
kubectl apply -f job2-batch-cronjob.yaml    
minikube dashboard
````
## See results
````
minikube service artic --url
````

## K8s CronJob
````
kubectl get jobs --watch
kubectl get pods --selector=job1-batch-cronjob-29007149-rg7xt
kubectl logs grpc-curl-job-29004415-mb5lb
````