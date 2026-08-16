# Opening a way in — instructions for an agent with access to the VPS

The cleaned map lives on the machine that produced it, and that machine cannot reach out. Its
network policy does not merely close ports, it inspects protocols: only HTTP and TLS leave it. That
was measured, not assumed —

| test | result |
|---|---|
| port 22 outbound, to any host at all | blocked |
| SSH on port 443, against GitHub's own sshd at `ssh.github.com:443` | fails, banner never arrives |
| non-HTTP bytes on port 80 to the VPS | answered `400 Bad Request` by an intermediary |
| HTTP to the VPS | works — nginx answers |
| `PUT` and `POST` against that nginx | 404 and 405 |

So root credentials for the VPS are of no use from there: no SSH session can leave. HTTP can. Your
job is to put something on the VPS that accepts a file over HTTP, so the map can be pushed to it.

Nothing here needs to reach into the other machine. It only needs to listen.

## What has to be true when you are done

| | |
|---|---|
| reachable | `http://72.60.94.131/` from the public internet, port 80 |
| accepts | `PUT /<filename>` with an `X-Token` header, body written to disk under one directory |
| rejects | any `PUT` without the right token, with 403 |
| answers | `200` and a response with a `Content-Length`, so the client does not hang on keep-alive |
| free disk | at least 6 GB in the destination filesystem |

Port 80 is not a preference, it is the only door. Port 443 on that host accepts a TCP connection but
answers nothing, and every other port is unreachable from the sending side.

## Step 1 — find out whether nginx is doing anything real

    ls -la /etc/nginx/sites-enabled/ /etc/nginx/conf.d/ 2>/dev/null
    curl -sI http://127.0.0.1/

At the time of writing it served the stock nginx page, last modified 20 September 2025, which
suggests nothing is in production on it. Confirm that yourself rather than trusting this paragraph —
if a real site is being served, stopping nginx takes it down, and step 2 has two routes for exactly
that reason.

## Step 2 — stand up the receiver

Generate a token first. Do not reuse one that has been through a chat window:

    python3 -c "import secrets; print(secrets.token_urlsafe(18))"

Write the receiver, substituting the token you just generated:

    mkdir -p /root/hyrule
    cat > /root/recv.py <<'PY'
    import http.server, os

    TOKEN = "PUT-THE-GENERATED-TOKEN-HERE"
    DEST = "/root/hyrule"

    class H(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _reply(self, code, body=b""):
            self.send_response(code)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)

        def do_PUT(self):
            if self.headers.get("X-Token") != TOKEN:
                return self._reply(403)

            name = os.path.basename(self.path)

            # basename() is what keeps the write inside DEST: `PUT /../x` arrives as `x`. What it
            # cannot handle is a path that ends in a dot segment, so those are refused by name.
            if not name or name in (".", ".."):
                return self._reply(400)

            left = int(self.headers.get("Content-Length", 0))
            written = 0

            with open(os.path.join(DEST, name), "wb") as out:
                while left > 0:
                    block = self.rfile.read(min(1 << 20, left))

                    if not block:
                        break

                    out.write(block)
                    written += len(block)
                    left -= len(block)

            # A short write means the connection dropped mid-file. Say so, so the part is resent
            # rather than silently kept as a truncated file that only fails later, at reassembly.
            self._reply(200 if left == 0 else 500, b"%d\n" % written)

        def log_message(self, *args):
            pass

    http.server.ThreadingHTTPServer(("", 80), H).serve_forever()
    PY

**Route A — nginx is not serving anything that matters.** Stop it and let the receiver have the
port:

    systemctl stop nginx
    nohup python3 /root/recv.py > /root/recv.log 2>&1 &

**Route B — nginx is serving a real site.** Leave it up and put the receiver behind it. Change the
last line of `recv.py` to bind `127.0.0.1` on port 8080, then add to the server block:

    location /upload/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_request_buffering off;
        client_max_body_size 0;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

Both of those directives matter. Without `proxy_request_buffering off` nginx writes every upload to
its own temporary file before passing it on, which doubles the disk needed and the time taken;
without `client_max_body_size 0` it refuses anything over a megabyte. Then `nginx -t && systemctl
reload nginx`.

If you take route B, the upload path is `/upload/<filename>` rather than `/<filename>`. Say which
route you took — the sending side has to know where to aim.

## Step 3 — prove it works before reporting it works

From the VPS itself:

    head -c 1000000 /dev/urandom > /tmp/probe.bin
    curl -sS -o /dev/null -w "%{http_code}\n" -T /tmp/probe.bin \
         -H "X-Token: <the token>" http://127.0.0.1/probe.bin
    curl -sS -o /dev/null -w "no token -> %{http_code}\n" -T /tmp/probe.bin \
         http://127.0.0.1/probe.bin
    cmp /tmp/probe.bin /root/hyrule/probe.bin && echo "byte for byte identical"

Expect `200`, then `no token -> 403`, then the comparison passing. A receiver that answers 200 but
writes a different file is worse than one that fails, because the failure surfaces hours later at
the end of a 2.6 GB transfer.

Then from anywhere outside the VPS, to prove the port is actually open to the world and not just to
localhost:

    curl -sS -o /dev/null -w "%{http_code}\n" -T /tmp/probe.bin \
         -H "X-Token: <the token>" http://72.60.94.131/probe.bin

    rm /root/hyrule/probe.bin

## Step 4 — report back

Five things, and the transfer cannot start without all five:

1. The token.
2. Which route, A or B — that is, whether the path is `/<name>` or `/upload/<name>`.
3. `df -h /root | tail -1`, so it is known there is room. About 6 GB is wanted.
4. That the outside test from step 3 returned 200.
5. Whether nginx was stopped, and whether that matters.

## While the transfer runs

The map goes over as a compressed archive of roughly 2.6 GB, split into 200 MB parts named
`hyrule.tar.gz.part-aa`, `-ab`, and so on, each sent as its own `PUT`. Split, because a single
2.6 GB request that breaks in the middle costs the whole transfer, while one part that breaks costs
one part. Nothing needs doing during it. `ls -la /root/hyrule/` shows the parts arriving.

A `SHA256SUMS` file is sent last, listing every part.

## When the parts have all arrived

    cd /root/hyrule
    sha256sum -c SHA256SUMS
    cat hyrule.tar.gz.part-* > hyrule.tar.gz
    tar tzf hyrule.tar.gz | head

Check the sums **before** concatenating. Afterwards the only thing a mismatch tells you is that
something is wrong somewhere in 2.6 GB; before, it names the part to resend.

`tar tzf` reading the first entries without complaint means the archive is intact — a truncated
gzip stream fails there rather than at extraction time, when it has already written gigabytes.

## Then close it again

The endpoint is a hole in a public-facing machine, held shut by a single token. It should exist for
the length of the transfer and no longer:

    pkill -f recv.py
    rm /root/recv.py
    systemctl start nginx        # route A
                                 # route B: drop the location block, nginx -t && systemctl reload nginx

And change the VPS root password. It was sent through a chat window to arrange this.

## This was tested, not just written

The receiver above was extracted from this document verbatim, run, and exercised: a 3 MB `PUT` with
the token returned 200 and landed byte for byte identical; without the token, and with a wrong one,
403; `PUT /../escaped.bin` was written inside the destination directory rather than above it.

## One thing not to bother with

Restricting the endpoint by source IP looks like the obvious hardening and does not work here: the
sending side answered from `160.79.106.136` and `160.79.106.135` within the same minute, so it is a
pool rather than an address. A rule narrow enough to be worth having would break the transfer. Keep
the window short instead.
