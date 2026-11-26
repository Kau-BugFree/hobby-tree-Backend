"""Run inside the API image with --network none. Only a synthetic loopback peer."""
import concurrent.futures
import ctypes as c
import json
import socket
import struct

import psycopg


def cyrus_probe():
    lib = c.CDLL('libsasl2.so.2')
    lib.sasl_client_init.argtypes = [c.c_void_p]
    lib.sasl_client_init.restype = c.c_int
    lib.sasl_client_new.argtypes = [c.c_char_p, c.c_char_p, c.c_char_p, c.c_char_p,
                                   c.c_void_p, c.c_uint, c.POINTER(c.c_void_p)]
    lib.sasl_client_new.restype = c.c_int
    lib.sasl_listmech.argtypes = [c.c_void_p, c.c_char_p, c.c_char_p, c.c_char_p,
                                 c.c_char_p, c.POINTER(c.c_char_p), c.POINTER(c.c_uint), c.POINTER(c.c_int)]
    lib.sasl_listmech.restype = c.c_int
    lib.sasl_client_start.argtypes = [c.c_void_p, c.c_char_p, c.POINTER(c.c_void_p),
                                     c.POINTER(c.c_char_p), c.POINTER(c.c_uint), c.POINTER(c.c_char_p)]
    lib.sasl_client_start.restype = c.c_int
    lib.sasl_dispose.argtypes = [c.POINTER(c.c_void_p)]
    lib.sasl_dispose.restype = None
    lib.sasl_done.argtypes = []
    lib.sasl_done.restype = None
    if lib.sasl_client_init(None) != 0:
        raise RuntimeError('CYRUS_INIT_FAILED')
    conn = c.c_void_p()
    try:
        if lib.sasl_client_new(b'ldap', b'synthetic.invalid', None, None, None, 0, c.byref(conn)) != 0:
            raise RuntimeError('CYRUS_CLIENT_FAILED')
        result, length, count = c.c_char_p(), c.c_uint(), c.c_int()
        if lib.sasl_listmech(conn, None, b'', b' ', b'', c.byref(result), c.byref(length), c.byref(count)) != 0:
            raise RuntimeError('CYRUS_ENUMERATION_FAILED')
        mechanisms = (result.value or b'').decode('ascii').split()
        if 'DIGEST-MD5' in mechanisms:
            raise RuntimeError('VULNERABLE_MECHANISM_AVAILABLE')
        interaction, client_out, out_length, selected = c.c_void_p(), c.c_char_p(), c.c_uint(), c.c_char_p()
        status = lib.sasl_client_start(conn, b'DIGEST-MD5', c.byref(interaction),
                                       c.byref(client_out), c.byref(out_length), c.byref(selected))
        if status != -4:  # SASL_NOMECH: cannot enter the vulnerable DIGEST-MD5 exchange.
            raise RuntimeError('DIGEST_MD5_NOT_REJECTED')
        return mechanisms
    finally:
        if conn.value:
            lib.sasl_dispose(c.byref(conn))
        lib.sasl_done()


def read_exact(peer, size):
    data = b''
    while len(data) < size:
        part = peer.recv(size - len(data))
        if not part:
            raise RuntimeError('INCOMPLETE_SYNTHETIC_STARTUP')
        data += part
    return data


def libpq_probe():
    def server(listener):
        peer, _ = listener.accept()
        with peer:
            peer.settimeout(5)
            size = struct.unpack('!I', read_exact(peer, 4))[0]
            if not 8 <= size <= 4096:
                raise RuntimeError('UNEXPECTED_STARTUP_SIZE')
            startup = read_exact(peer, size - 4)
            if startup[:4] != struct.pack('!I', 196608):
                raise RuntimeError('UNEXPECTED_PROTOCOL')
            payload = struct.pack('!I', 10) + b'DIGEST-MD5\0\0'
            peer.sendall(b'R' + struct.pack('!I', 4 + len(payload)) + payload)
            # No password/SASLInitialResponse may be sent to this unsupported mechanism.
            return peer.recv(1)

    with socket.socket() as listener:
        listener.settimeout(5)
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(server, listener)
            try:
                with psycopg.connect(hostaddr='127.0.0.1', port=listener.getsockname()[1],
                                     user='synthetic', password='synthetic', dbname='synthetic',
                                     sslmode='disable', gssencmode='disable', connect_timeout=3):
                    raise RuntimeError('UNSUPPORTED_AUTH_ACCEPTED')
            except psycopg.OperationalError as exc:
                if "none of the server's SASL authentication mechanisms are supported" not in str(exc):
                    raise RuntimeError('UNEXPECTED_LIBPQ_FAILURE') from None
            if future.result(timeout=6) not in (b'', b'X'):
                raise RuntimeError('AUTH_RESPONSE_SENT')


if __name__ == '__main__':
    mechanisms = cyrus_probe()
    libpq_probe()
    print(json.dumps({'cyrusMechanisms': mechanisms, 'digestMd5Rejected': True,
                      'libpqUnsupportedSaslRejected': True, 'externalCalls': 0}))
