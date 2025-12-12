# Система проверки плагиата (микросервисная архитектура)

## 1. Как запустить?
- `docker-compose build`
- `docker-compose up`
- API Gateway находится по адресу `localhost:8080`

---

## 2. Архитектура системы

Система состоит из трёх независимых микросервисов:

1. API Gateway (порт 8080)  
   Принимает запросы клиентов, пересылает файлы в Storage, запускает анализ на Processing, предоставляет единый REST-интерфейс.

2. Storage Service  
   Сохраняет файлы на диск, хранит информацию о загрузках в PostgreSQL, выдаёт файлы по ID.

3. Processing Service  
   Загружает файл из Storage, вычисляет хэш, определяет плагиат, обновляет отчёты в базе данных.

4. В качестве Базы Данных используется PostgreSQL  
   Хранит две таблицы:
   - submissions(id, student, work_id, file_path)
   - reports(id, submission_id, hash, plagiat)

Обмен между сервисами осуществляется через HTTP.

---

## 3. Пользовательские сценарии и взаимодействие микросервисов

### Сценарий 1. Загрузка работы

1. Клиент отправляет запрос в API Gateway:  
   POST /upload?student=...&workId=...
2. API Gateway пересылает файл в Storage:  
   POST /store
3. Storage сохраняет файл, создаёт запись в БД и возвращает submissionId.
4. API Gateway вызывает Processing:  
   POST /analyze?submissionId=...
5. Processing выполняет анализ и сохраняет плагиат работа или нет.
6. API Gateway возвращает клиенту submissionId.

---

### Сценарий 2. Получение отчётов по заданию

1. Клиент обращается в API Gateway:  
   GET /works/{workId}/reports
2. Gateway перенаправляет запрос в Processing:  
   GET /work_reports?workId=...
3. Processing делает JOIN submissions и reports, формирует JSON.
4. API Gateway возвращает ответ клиенту.

---

## 4. REST API

### API Gateway
- POST /upload?student=&workId= — загрузка работы  
- **Пример запроса**: `curl -X POST "http://localhost:8080/upload?student=Pupka&workId=122" --data-binary "@test.txt" -H "Content-Type: application/octet-stream"
`
- GET /works/{workId}/reports — получение отчётов
- **Пример запроса**: `curl -X GET "http://localhost:8080/works/122/reports"`

### Storage
- POST /store?student=&workId= — сохранить файл  
- GET /file?id= — получить файл

### Processing
- POST /analyze?submissionId= — анализ работы  
- GET /work_reports?workId= — отчёты по заданию
