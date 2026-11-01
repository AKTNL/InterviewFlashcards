"""Read-only HTTPS release checks. Never disables certificate/hostname verification."""
import argparse
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True, help='HTTPS origin, without a path')
    parser.add_argument('--http-url', required=True, help='HTTP origin to check for redirect')
    parser.add_argument('--cafile', help='Explicit trusted CA/certificate for the local rehearsal only')
    args = parser.parse_args()
    base, plain = args.base_url.rstrip('/'), args.http_url.rstrip('/')
    assert urllib.parse.urlsplit(base).scheme == 'https', 'HTTPS required'
    assert urllib.parse.urlsplit(plain).scheme == 'http', 'HTTP redirect origin required'
    context = ssl.create_default_context(cafile=args.cafile)
    opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=context))

    def get(url):
        try:
            response = opener.open(url, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    status, headers, _ = get(plain + '/cards?release-check=1')
    assert status == 308 and headers['Location'] == base + '/cards?release-check=1', 'Canonical HTTPS redirect'
    status, headers, html = get(base + '/')
    assert status == 200 and b'id="app"' in html, 'Built application shell'
    assert headers.get('Strict-Transport-Security') == 'max-age=31536000', 'HSTS'
    assert headers.get('X-Content-Type-Options') == 'nosniff', 'Content type protection'
    assert 'no-cache' in headers.get('Cache-Control', ''), 'Revalidate shell after upgrade'
    script = re.search(rb'<script[^>]+src="([^"]+)"', html)
    assert script, 'Built entry script'
    status, headers, _ = get(urllib.parse.urljoin(base, script[1].decode()))
    assert status == 200 and 'javascript' in headers.get('Content-Type', ''), 'JavaScript MIME'
    assert headers.get('Strict-Transport-Security') and headers.get('X-Content-Type-Options') == 'nosniff', 'Static security headers'
    status, headers, data = get(base + '/api/health')
    health = json.loads(data)
    assert status == 200 and health['data']['database'] == 'UP', 'Database and migrations'
    assert health['data']['learningTimezone'] == 'Asia/Shanghai', 'Server learning timezone'
    assert headers.get('X-Request-ID') == health['meta']['requestId'], 'Traceable request ID'
    assert headers.get('Strict-Transport-Security'), 'API HSTS'
    for endpoint in ['/api/sync/snapshot', '/api/backups', '/api/sources']:
        status, _, data = get(base + endpoint)
        assert status == 401 and json.loads(data)['error']['code'] == 'UNAUTHENTICATED', 'Private endpoint: ' + endpoint
    for endpoint in ['/assets/release-missing.js', '/assets/release-missing.mjs', '/pdfjs/release-missing.wasm']:
        status, headers, _ = get(base + endpoint)
        assert status == 404 and headers.get('Strict-Transport-Security'), 'Static 404: ' + endpoint
    status, _, data = get(base + '/cards/a-route-for-client')
    assert status == 200 and b'id="app"' in data, 'SPA route fallback'
    print(json.dumps({'result': 'PASS', 'origin': base, 'tlsVerified': True,
                      'checks': ['redirect', 'TLS hostname/trust', 'HSTS and MIME', 'cache', 'SPA and static 404',
                                 'database health', 'request ID', 'unauthenticated data rejection']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
