# Tern

./mvnw clean package
docker-compose up --build

http://localhost:8080/h2-console/

curl -d '{"text":"jouda"}' -H "Content-Type: application/json" -X POST http://localhost:8080    
curl http://localhost:8080/

## Locust
locust    
http://0.0.0.0:8089/

Host: http://localhost:8080

