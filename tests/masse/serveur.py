#!/usr/bin/env python3
"""Sert docs/masse en local pour la suite de tests.

Un simple SimpleHTTPRequestHandler ne suffit pas : il annonce le HTML sans
charset, et l'app est en UTF-8 — sans ça les accents deviennent du mojibake
et les contrôles de contenu échouent pour une raison qui n'a rien à voir.
    python3 serveur.py [port]
"""
import functools, http.server, os, sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else int(os.environ.get('PORT_TEST', 8765))
RACINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'masse')

class H(http.server.SimpleHTTPRequestHandler):
    def guess_type(self, path):
        t = super().guess_type(path)
        return t + '; charset=utf-8' if t == 'text/html' else t
    def log_message(self, *a):
        pass

http.server.HTTPServer(('127.0.0.1', PORT),
    functools.partial(H, directory=os.path.abspath(RACINE))).serve_forever()
