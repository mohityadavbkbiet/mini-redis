"""Talks to the server with redis-py, the official Python Redis client: proof that the RESP protocol is real.
Usage: pip install redis && python3 compat_test.py [port]"""
import sys, time
import redis

port = int(sys.argv[1]) if len(sys.argv) > 1 else 6380
r = redis.Redis(host="127.0.0.1", port=port, decode_responses=True, protocol=2)  # we speak RESP2
r.flushall()

assert r.ping() is True
assert r.set("name", "mohit") is True
assert r.get("name") == "mohit"
assert r.get("missing") is None
assert r.set("k", "v", nx=True) is True and r.set("k", "v2", nx=True) is None
assert r.incr("counter") == 1 and r.incrby("counter", 9) == 10 and r.decr("counter") == 9
assert r.set("temp", "x", px=200) is True and 0 <= r.pttl("temp") <= 200
time.sleep(0.35); assert r.get("temp") is None and r.ttl("temp") == -2
r.set("e", "v"); assert r.expire("e", 100) is True and 99 <= r.ttl("e") <= 100 and r.persist("e") is True and r.ttl("e") == -1
assert r.rpush("q", "a", "b", "c") == 3 and r.lpush("q", "z") == 4
assert r.lrange("q", 0, -1) == ["z", "a", "b", "c"] and r.lpop("q") == "z" and r.rpop("q") == "c" and r.llen("q") == 2
assert r.hset("user", mapping={"name": "mohit", "city": "delhi"}) == 2
assert r.hget("user", "city") == "delhi" and r.hgetall("user") == {"name": "mohit", "city": "delhi"} and r.hlen("user") == 2
assert r.mset({"a": "1", "b": "2"}) is True and r.mget(["a", "b", "nope"]) == ["1", "2", None]
assert sorted(r.keys("*")) == sorted(r.keys()) and r.exists("a", "b", "nope") == 2 and r.type("q") == "list"
assert r.delete("a", "b") == 2
try:
    r.get("q"); raise SystemExit("expected WRONGTYPE")
except redis.ResponseError as e:
    assert "WRONGTYPE" in str(e)
p = r.pipeline(transaction=False)
for i in range(100): p.set(f"p{i}", i)
assert all(p.execute()) and r.get("p99") == "99"
print("redis-py compatibility: all checks passed, dbsize =", r.dbsize())
