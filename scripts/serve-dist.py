from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, _format, *_args):
        pass


if __name__ == "__main__":
    dist = Path(__file__).resolve().parent.parent / "dist"
    server = ThreadingHTTPServer(("0.0.0.0", 5173), partial(QuietHandler, directory=dist))
    server.serve_forever()
