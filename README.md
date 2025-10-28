# Tern

The Arctic tern holds the record for the longest migration route of any bird, traveling from the Arctic to the Antarctic and back again every year. The Antarctic tern is a species of tern that is native to the Antarctic region.    
We want to migrate a service (Tern service) from legacy (Antarctic version) to a new version (Arctic version). We want Arctic to be receiving the traffic and synch with Antarctic, without falling in the issue of cascading HTTP.    
We want to use Istio to maintain 2 deployments of the same app.    
We can also simply use docker-compose, if we do not want to start locally Minikube.    
The 2 services communicate using gRPC. On the gRPC callback, we make a call to Tapi using WebClient. Tapi exposes a a very large CSV file that we want to read using streaming, to avoid the issue of loading a large data in memory.

## Setup

Make sure you have Docker and maven 3.6.3 installed.

### Option 1: using docker-compose

mvn clean package
docker-compose up --build

### Option 2: using kubernetes and Minikube

Make sure you have Minikube installed.    

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

```minikube service artic```    

````
curl -d '{"text":"some-text"}' -H "Content-Type: application/json" -X POST {artic_host}    
curl {artic_host}
````

#### Istio
Make sure you have istio 1.18 installed.    

````
# to download istio:    
curl -L https://istio.io/downloadIstio | sh -
# from inside istio folder
export PATH=$PWD/bin:$PATH
istioctl install --set profile=demo -y

kubectl label namespace default istio-injection=enabled    

kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/prometheus.yaml    
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/kiali.yaml    
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/grafana.yaml    

istioctl dashboard kiali
````


#### Locust
In order to have a loadTest and see traffic animation on Kiali

<img width="766" alt="Screenshot 2023-07-02 at 16 01 47" src="https://github.com/Jouda-Hidri/Tern/assets/30729085/f7c67457-2a28-4841-9a17-edfa6f826a08">

To setup Locust, clone this project https://github.com/Jouda-Hidri/tern-lt

#### Tapi
On the gRPC callback, Tapi is called    
<img width="768" alt="Screenshot 2023-07-03 at 13 12 50" src="https://github.com/Jouda-Hidri/Tern/assets/30729085/17763716-9c9e-4247-9e4b-70ad0819b54b">    

To setup Tapi, clone this project: https://github.com/Jouda-Hidri/tapi


