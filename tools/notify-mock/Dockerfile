FROM python:3.12-alpine
WORKDIR /app
COPY server.py .
USER nobody
EXPOSE 8090
CMD ["python", "-u", "server.py"]
