"""Mock LM Studio pour les tests d'integration du LLM-as-judge.

Expose l'API OpenAI-compatible minimale consommee par LLMJudgeValidator :

- ``GET /v1/models``            -> un seul modele (id aligne sur le TOML)
- ``POST /v1/chat/completions`` -> verdict FALSE_POSITIVE systematique

Le verdict est place dans ``message.reasoning_content`` (comportement
LM Studio + Qwen thinking observe en spec section 1.6) pour exercer le
parser nominal de ``_extract_json_payload``.

Objectif : rendre deterministe le test de bout en bout du canal
``discarded_entities`` (proto, option C) sans dependre d'un vrai GPU/LLM :
toute entite auditee par le judge est ecartee, donc le champ
``discarded_entities`` de la reponse gRPC doit etre peuple.

Lance par Testcontainers (voir JudgeDiscardedEntitiesSmokeIT) :
    python /mock/mock_lm_studio.py
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 1234

# Doit matcher la resolution de modele du validator (exact ou fuzzy
# "qwen3.6"+"a3b" hors blacklist fine-tunes).
MODEL_ID = "qwen3.6-35b-a3b-instruct-pure"

VERDICT = {
    "verdict": "FALSE_POSITIVE",
    "confidence": 0.99,
    "reason": "mock judge: always FALSE_POSITIVE",
}


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._send_json({"data": [{"id": MODEL_ID}]})
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        # Draine le corps pour ne pas casser le keep-alive.
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)
        if self.path.rstrip("/").endswith("/chat/completions"):
            self._send_json(
                {
                    "choices": [
                        {
                            "message": {
                                "reasoning_content": json.dumps(VERDICT),
                                "content": "",
                            }
                        }
                    ],
                    "usage": {
                        "prompt_tokens": 1,
                        "completion_tokens": 1,
                        "completion_tokens_details": {"reasoning_tokens": 0},
                        "total_tokens": 2,
                    },
                }
            )
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):  # noqa: A002 - signature imposee
        print("[mock-lm-studio] " + (fmt % args), flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Mock LM Studio listening on port {PORT}", flush=True)
    server.serve_forever()
