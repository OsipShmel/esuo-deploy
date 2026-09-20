FROM python:3.12-alpine
WORKDIR /app
COPY simulator.py .
USER nobody
EXPOSE 8091
CMD ["python", "-u", "simulator.py"]
