# Tern
Make sure you have Docker, Minikube and maven 3.6.3 installed.    

````
minikube start #(using virtualbox)    
minikube addons enable metrics-server    
eval $(minikube docker-env)    
mvn clean install    
docker build -t tern .    
cd deployment    
kubectl apply -f artic.yaml    
kubectl apply -f antartic.yaml    
kubectl apply -f postgres.yaml
minikube dashboard
````

```kubectl service artic```    

````
curl -d '{"text":"some-text"}' -H "Content-Type: application/json" -X POST {artic_host}    
curl {artic_host}
````

## Istio
Make sure you have istio 1.18 installed.    

````
kubectl label namespace default istio-injection=enabled    

kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/prometheus.yaml    
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/kiali.yaml    
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/grafana.yaml    

istioctl dashboard kiali
````