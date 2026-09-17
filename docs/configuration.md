# Configuration

Use Java 21 and configure provider credentials outside tracked source files. `.env.example` documents variable names only; use shell exports or your process manager to inject them.

| Integration | Environment variables |
|---|---|
| OpenAI-compatible gateway | `OPENAI_API_KEY`, optional `OPENAI_BASE_URL` |
| DashScope | `DASHSCOPE_API_KEY`, optional `DASHSCOPE_BASE_URL` |
| Financial Datasets | `FINANCIAL_DATASETS_API_KEY` |
| Search | `TAVILY_API_KEY` |
| X | `X_API_KEY` |
| Finnhub | `FINNHUB_API_KEY` |
| FRED / BLS | `FRED_API_KEY`, `BLS_API_KEY` |
| Chroma | `CHROMA_BASE_URL` |
| IBKR | `IBKR_ACCESS_TOKEN`, `IBKR_MCP_URL`; OAuth configuration as needed |

Model routing is configured under `agent.model-routing`; DashScope connection settings are under `agent.qwen`. Checked-in model names reflect this checkout, not a guarantee that your account supports them. Select models available to your account and verify context/output settings accordingly.

Private YAML files are imported from the project `.secrets` directory and the user's configuration directory. Standard Spring configuration precedence applies. Never replace the empty example files with actual secrets in a commit.

Session/memory/scheduler/event data and IBKR token files may be stored under the user's home directory by default. Review `application.yml` and configuration records when choosing an isolated deployment. File tools use the configured filesystem root.

The default application port is 8081. External providers are optional individually, but research that depends on them requires successful configuration. Chroma must match the client API expected by this checkout; no container version is pinned here.
