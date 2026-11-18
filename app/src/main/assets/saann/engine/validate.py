TYPES = {"string": str, "boolean": bool, "number": (int, float),
         "integer": int, "array": list, "object": dict}

def validate(obj, schema, path="$"):
    t = schema.get("type")
    if t:
        ok = isinstance(obj, TYPES[t])
        if t in ("number", "integer") and isinstance(obj, bool):
            ok = False
        if not ok:
            return [f"{path}: expected {t}"]
    errs = []
    if "enum" in schema and obj not in schema["enum"]:
        errs.append(f"{path}: not one of {schema['enum']}")
    if t == "object":
        for k in schema.get("required", []):
            if k not in obj:
                errs.append(f"{path}.{k} missing")
        for k, sub in schema.get("properties", {}).items():
            if k in obj:
                errs += validate(obj[k], sub, f"{path}.{k}")
    if t == "array":
        for i, x in enumerate(obj):
            errs += validate(x, schema.get("items", {}), f"{path}[{i}]")
    return errs
