FROM python:3.12-slim

WORKDIR /workspace/navigation/frontend
RUN useradd --create-home --uid 10001 appuser \
  && chown -R appuser:appuser /workspace

EXPOSE 8787

USER appuser
CMD ["python3", "dev_server.py"]
