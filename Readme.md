## Related Repositories
*Frontend* : https://github.com/syityx/Video-Frontend

## Skills
**SpringBoot + RocketMQ + Redis + MySQL + MyBatis Plus + MinIO + FFmpeg**

## How To Use:
**RocketMQ** 

_version_: `5.4.0`
```cmd
cd C:\MyScripts\rocketmq\bin
start mqnamesrv.cmd
start mqbroker.cmd -n 127.0.0.1:9876 autoCreateTopicEnable=true
mqadmin clusterList -n 127.0.0.1:9876
```
---
**Minio** 

_version_: `RELEASE.2025-07-23T15-54-02Z`

`cd E:\minio\bin` `双击start.bat`
```start.bat
@echo off
set MINIO_ROOT_USER=root
set MINIO_ROOT_PASSWORD=12345678
E:\minio\bin\minio.exe server E:\minio\data --console-address "127.0.0.1:9000" --address "127.0.0.1:9091" > E:\minio\logs\minio.log 2>&1
```
---
**Redis**

_version_: `3.0.504`
```cmd
cd E:\Redis-x64-3.0.504
redis-server.exe redis.windows.conf
```
---
**FFmpeg**

_version_: `7.1.1-essentials_build-www.gyan.dev`

---

## Port
 - ServerPort(BackEnd): `9090`
 - FrontEnd: `5173`
 - MySQL: `3306`
 - Redis: `6379`
 - RocketMQ: `9876`
 - MinIO: `9000` (API), `9091` (Console)

