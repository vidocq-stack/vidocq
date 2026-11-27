# A gRPC server on grpcio (the gRPC C core), for GrpcPythonInteropTest: raw bytes in and out, no protobuf.
# Arguments: cert.pem key.pem [extra sys.path entry]. Prints "ready <cleartext port> <tls port>".
import sys

if len(sys.argv) > 3 and sys.argv[3]:
    sys.path.insert(0, sys.argv[3])

import time
from concurrent import futures

import grpc


def echo(request, context):
    metadata = dict(context.invocation_metadata())
    context.send_initial_metadata((("x-served-by", "grpcio"),))
    context.set_trailing_metadata((
        ("x-tenant-seen", metadata.get("x-tenant", "none")),
        ("x-deadline-seen", "yes" if context.time_remaining() is not None else "no"),
    ))
    return request


def fail(request, context):
    context.abort(grpc.StatusCode.FAILED_PRECONDITION, "café is closed: 100%")


def slow(request, context):
    time.sleep(3)
    return request


handlers = {
    "Echo": grpc.unary_unary_rpc_method_handler(echo),
    "Fail": grpc.unary_unary_rpc_method_handler(fail),
    "Slow": grpc.unary_unary_rpc_method_handler(slow),
}
limits = [("grpc.max_receive_message_length", 8 * 1024 * 1024), ("grpc.max_send_message_length", 8 * 1024 * 1024)]
server = grpc.server(futures.ThreadPoolExecutor(max_workers=8), options=limits)
server.add_generic_rpc_handlers((grpc.method_handlers_generic_handler("interop.Echo", handlers),))
plain = server.add_insecure_port("127.0.0.1:0")
with open(sys.argv[2], "rb") as key, open(sys.argv[1], "rb") as cert:
    credentials = grpc.ssl_server_credentials(((key.read(), cert.read()),))
secure = server.add_secure_port("127.0.0.1:0", credentials)
server.start()
print("ready %d %d" % (plain, secure), flush=True)
sys.stdin.read()  # runs until the test closes stdin
server.stop(0)
