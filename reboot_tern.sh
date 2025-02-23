#When changes are introduced in the code
#We need to rebuild and redeploy Tern pods
#This doesnt require restarting Minikube
eval $(minikube docker-env)
mvn clean install
docker build -t tern .
kubectl delete -f deployment/artic.yaml
kubectl apply -f deployment/artic.yaml
kubectl delete -f deployment/antarctic.yaml
kubectl apply -f deployment/antarctic.yaml
