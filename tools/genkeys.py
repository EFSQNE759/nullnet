import base64, os, sys
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

tools = os.path.dirname(os.path.abspath(__file__))
sk = Ed25519PrivateKey.generate()
priv = sk.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
pub = sk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)

with open(os.path.join(tools, "update_key_private.b64"), "wb") as f:
    f.write(base64.b64encode(priv))
with open(os.path.join(tools, "update_key_public.b64"), "wb") as f:
    f.write(base64.b64encode(pub))

print("UPDATE_PUBLIC_KEY_B64=" + base64.b64encode(pub).decode())
print("PRIVATE_B64=" + base64.b64encode(priv).decode())
