FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN useradd --uid 1000 --create-home app
WORKDIR /srv
COPY pyproject.toml ./
COPY boilerd ./boilerd
RUN pip install .
USER app
EXPOSE 8020
HEALTHCHECK --interval=60s --timeout=5s --start-period=30s CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8020/api/v1/health', timeout=4).status == 200 else 1)"
CMD ["uvicorn", "boilerd.app:create_app_from_env", "--factory", "--host", "0.0.0.0", "--port", "8020", "--proxy-headers", "--forwarded-allow-ips", "*"]
