# deploy

????????????????????????????

## ????

```powershell
cd deploy
copy .env.example .env
docker compose -f docker-compose-env.yml --env-file .env up -d
```

## ????

```powershell
docker compose -f docker-compose-env.yml --env-file .env ps
```

## ????

```powershell
docker compose -f docker-compose-env.yml --env-file .env logs -f mysql
docker compose -f docker-compose-env.yml --env-file .env logs -f redis
docker compose -f docker-compose-env.yml --env-file .env logs -f minio
```

## ????

```powershell
docker compose -f docker-compose-env.yml --env-file .env down
```

## ?????

?? volume ??? MySQL?Redis?MinIO ????????????

```powershell
docker compose -f docker-compose-env.yml --env-file .env down -v
```

## ????

- MySQL: `localhost:3306`
- Redis: `localhost:6379`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`

MinIO ?? bucket ? `.env` ?? `MINIO_BUCKET`?
