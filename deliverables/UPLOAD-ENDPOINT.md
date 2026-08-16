# Opening a way in — instructions for an agent with access to the VPS

## Read this first: you are probably not the machine this is about

Three machines are involved and confusing two of them wastes a lot of work.

| | |
|---|---|
| **the sandbox** | a remote container, hostname `vm`, where the map was cleaned. Ephemeral. Egress restricted. |
| **the workstation** | the owner's PC. Ordinary internet access, OpenSSH client, and its own copy of the map. |
| **the VPS** | `72.60.94.131`, Debian, nginx on port 80. |

The table below was measured **in the sandbox and nowhere else**. It says nothing about the
workstation, and if you run it from the workstation you will get the opposite answer on every line
— that is expected, not a contradiction. The workstation reaches `72.60.94.131:22` and gets an
OpenSSH banner straight back.

| test, run in the sandbox | result |
|---|---|
| TCP to `github.com:22` | no connection, times out |
| TCP to `72.60.94.131:22` | no connection, times out |
| SSH banner from `ssh.github.com:443` | never arrives |
| non-HTTP bytes on port 80 to the VPS | answered `400 Bad Request` by an intermediary |
| HTTP to the VPS | works — nginx answers |
| `PUT` and `POST` against that nginx | 404 and 405 |

So root credentials for the VPS are of no use **from the sandbox**: no SSH session leaves it. HTTP
does.

## And read this second: this is very likely unnecessary

This document exists for one narrow case — moving the sandbox's copy of the cleaned map out before
the container is reclaimed. It is worth doing only if that copy is the one that is wanted.

It usually is not. The workstation has an OpenSSH client and direct SSH to the VPS, so anything it
holds it can send itself, encrypted and authenticated, with `sftp` and `reput` to resume. And the
deletion list reproduces the cleaned map exactly from the original, which is the whole reason the
list is the deliverable rather than the map.

**Do not stand this up before checking whether the map already exists where it is needed.**
`tools/spotcheck.py` answers that without moving anything: run it against the copy in question and
against the untouched original, and compare. If the copy is already clean, nothing needs to travel
and none of the rest of this applies.

If you do proceed: your job is to put something on the VPS that accepts a file over HTTP. Nothing
here needs to reach into the sandbox. It only needs to listen.

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

    install -d -m 750 -o www-data -g www-data /srv/hyrule
    cat > /usr/local/bin/recv.py <<'PY'
    import http.server, os, re, shutil, urllib.parse

    TOKEN = "PUT-THE-GENERATED-TOKEN-HERE"
    DEST = "/srv/hyrule"
    MAX_PART = 256 << 20          # no part is 200 MB by accident; anything larger is not ours
    KEEP_FREE = 1 << 30           # stop before filling the disk out from under the machine

    # Names are matched, not sanitised. Everything sent is `hyrule.tar.gz.part-NNN` or `SHA256SUMS`,
    # so the whole question of traversal, encoding and query strings goes away: what does not match
    # is refused. Sanitising invites an argument about whether the sanitising is complete —
    # `self.path` is not URL-decoded by BaseHTTPRequestHandler, so a `%2F` or a `?x=1` arrives
    # verbatim and lands in the filename.
    ALLOWED = re.compile(r"\A(hyrule\.tar\.gz\.part-[0-9]{3}|SHA256SUMS)\Z")

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

            path = urllib.parse.urlsplit(self.path).path
            name = urllib.parse.unquote(path).lstrip("/")

            if not ALLOWED.match(name):
                return self._reply(400, b"unexpected name\n")

            left = int(self.headers.get("Content-Length", 0))

            if left <= 0 or left > MAX_PART:
                return self._reply(413, b"bad size\n")

            if shutil.disk_usage(DEST).free - left < KEEP_FREE:
                return self._reply(507, b"not enough room\n")

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

    http.server.ThreadingHTTPServer(("127.0.0.1", 8080), H).serve_forever()
    PY

Run it as a service, unprivileged. It binds loopback on 8080, so it needs neither root nor a
privileged port, and it cannot be reached from outside except through nginx:

    cat > /etc/systemd/system/recv.service <<'UNIT'
    [Service]
    ExecStart=/usr/bin/python3 /usr/local/bin/recv.py
    User=www-data
    Group=www-data
    ProtectSystem=strict
    ReadWritePaths=/srv/hyrule
    PrivateTmp=true
    NoNewPrivileges=true
    UNIT
    systemctl daemon-reload && systemctl start recv

nginx stays up and serves whatever it was serving. Add one location to the server block:

    location /upload/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_request_buffering off;
        client_max_body_size 0;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

Both of the first two directives matter. Without `proxy_request_buffering off` nginx writes every
upload to its own temporary file before passing it on, which doubles the disk needed and the time
taken; without `client_max_body_size 0` it refuses anything over a megabyte. Then `nginx -t &&
systemctl reload nginx`.

**Put it behind TLS if you possibly can.** Over plain HTTP the token and the archive cross the
internet in the clear, and the token is write access to a public machine. There is no domain and no
certificate authority in play, so a self-signed certificate on port 443 is what is available:

    openssl req -x509 -newkey rsa:2048 -nodes -days 7 \
        -keyout /etc/ssl/private/hyrule.key -out /etc/ssl/certs/hyrule.crt \
        -subj "/CN=72.60.94.131"

listened on with `ssl_certificate`, `ssl_certificate_key` and `listen 443 ssl;`. The sending side
will connect with certificate verification off, since a self-signed certificate cannot be verified —
which means this stops someone reading the token off the wire, not someone who can already redirect
traffic. It is a real improvement over plaintext and not a substitute for the short window.

Say which you ended up with, `http://` or `https://` — the sending side has to know where to aim.

## Step 3 — prove it works before reporting it works

From the VPS itself:

    head -c 1000000 /dev/urandom > /tmp/hyrule.tar.gz.part-999
    curl -sS -o /dev/null -w "with token   -> %{http_code}\n" -T /tmp/hyrule.tar.gz.part-999 \
         -H "X-Token: <the token>" http://127.0.0.1/upload/hyrule.tar.gz.part-999
    curl -sS -o /dev/null -w "no token     -> %{http_code}\n" -T /tmp/hyrule.tar.gz.part-999 \
         http://127.0.0.1/upload/hyrule.tar.gz.part-999
    curl -sS -o /dev/null -w "wrong name   -> %{http_code}\n" -T /tmp/hyrule.tar.gz.part-999 \
         -H "X-Token: <the token>" http://127.0.0.1/upload/../../etc/passwd
    cmp /tmp/hyrule.tar.gz.part-999 /srv/hyrule/hyrule.tar.gz.part-999 && echo "identical"

Expect `200`, `403`, `400`, then the comparison passing. A receiver that answers 200 but writes a
different file is worse than one that fails, because the failure surfaces hours later at the end of
a 2.6 GB transfer.

Then from a machine that is not the VPS, to prove the port is open to the world and not just to
localhost — the workstation can do this:

    curl -sS -o /dev/null -w "%{http_code}\n" -T /tmp/hyrule.tar.gz.part-999 \
         -H "X-Token: <the token>" http://72.60.94.131/upload/hyrule.tar.gz.part-999

    rm /srv/hyrule/hyrule.tar.gz.part-999

## Step 4 — report back

Five things, and the transfer cannot start without all five:

1. The token.
2. The full base URL, scheme included — `http://72.60.94.131/upload/` or `https://…`.
3. `df -h /srv | tail -1`, so it is known there is room. About 3 GB is wanted for the parts, 6 GB
   if the archive is to be unpacked on the VPS as well.
4. That the outside test from step 3 returned 200.
5. Whether anything on the VPS had to be changed to make room for this, and whether that matters.

## While the transfer runs

The map goes over as a compressed archive of roughly 2.6 GB, split into 200 MB parts named
`hyrule.tar.gz.part-000`, `-001`, and so on, each sent as its own `PUT`. Split, because a single
2.6 GB request that breaks in the middle costs the whole transfer, while one part that breaks costs
one part. Nothing needs doing during it. `ls -la /srv/hyrule/` shows the parts arriving.

A `SHA256SUMS` file is sent last, listing every part.

## When the parts have all arrived

    cd /srv/hyrule
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

    systemctl stop recv && systemctl disable recv
    rm /etc/systemd/system/recv.service /usr/local/bin/recv.py && systemctl daemon-reload
    # drop the location block, then: nginx -t && systemctl reload nginx

The VPS root password is a separate matter and a more urgent one. It was sent through a chat window
to arrange this, which means it is exposed **now**, not once the transfer finishes. Change it before
anything else here, and prefer a key pair afterwards so no password needs sending again.

## What was tested, and what was not

Both receivers were extracted from this document verbatim and run.

The first draft named its file with `os.path.basename(self.path)`. It survived a 3 MB round trip
byte for byte, refused a missing and a wrong token with 403, and kept `PUT /../escaped.bin` inside
the destination directory. That test is narrower than it sounds, though: `BaseHTTPRequestHandler`
does not decode `self.path`, so `%2F` and a trailing query string arrive verbatim and land in the
filename — `PUT /part-000?x=1` created a file called `part-000?x=1`. Not an escape, but not covered
by what was tested either.

The receiver above matches the name against a pattern instead of sanitising it, which removes the
question rather than answering it. Exercised the same way:

| | |
|---|---|
| `PUT /hyrule.tar.gz.part-007` with the token | 200, byte for byte identical |
| `PUT /SHA256SUMS` with the token | 200 |
| the same, no token | 403 |
| the same, wrong token | 403 |
| `PUT /hyrule.tar.gz.part-007?x=1` | 200, written as `hyrule.tar.gz.part-007` |
| `PUT /..%2F..%2Fescaped.bin` | 400 |
| `PUT /../../etc/passwd` | 400 |
| `PUT /whatever.bin` | 400 |
| 300 MB body | 413, nothing written |

Run step 3 anyway and believe that, not this table. It was measured on a different machine, with a
different Python, and behind no nginx.

## One thing not to bother with

Restricting the endpoint by source IP looks like the obvious hardening and does not work here: the
sending side answered from `160.79.106.136` and `160.79.106.135` within the same minute, so it is a
pool rather than an address. A rule narrow enough to be worth having would break the transfer. Keep
the window short instead.
