"""벤치마크할 음성 인식 모델을 Mac 에 받아 두고, 이번 실행 목록(bench/build/stt/engines.json)을 만든다.

모델은 sherpa-onnx 가 배포하는 압축 파일에서 engines.json 에 적힌 파일만 꺼내
~/.cache/local-trans-bench/stt-models/<id>/ 에 둔다.

사용: python3 bench/stt/fetch_models.py [모델 id ...] [--threads N] [--limit N]
      id 를 생략하면 engines.json 에서 optional 이 아닌 모델을 모두 고른다.
"""
import argparse
import json
import subprocess
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = Path.home() / ".cache" / "local-trans-bench" / "stt-models"
APP_CACHE = Path.home() / ".cache" / "local-trans-models"  # scripts/push-models.sh 가 풀어 둔 곳
RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"


def ensure(engine):
    folder = CACHE / engine["id"]
    names = list(engine["files"].values())
    if all((folder / name).is_file() for name in names):
        return folder
    folder.mkdir(parents=True, exist_ok=True)

    unpacked = APP_CACHE / engine["archive"]
    if all((unpacked / name).is_file() for name in names):
        for name in names:
            (folder / name).write_bytes((unpacked / name).read_bytes())
        return folder

    archive = CACHE / "archives" / f"{engine['archive']}.tar.bz2"
    if not archive.is_file():
        archive.parent.mkdir(parents=True, exist_ok=True)
        print(f"{engine['id']}: 내려받는 중 ({engine['archive']})")
        part = archive.with_name(archive.name + ".part")
        # 큰 파일은 연결이 멈추는 일이 있어, 느려지면 끊고 이어받는다.
        subprocess.run(
            ["curl", "-sS", "-L", "--fail", "--http1.1", "--retry", "8", "--retry-delay", "3", "--retry-all-errors",
             "--speed-limit", "20000", "--speed-time", "30", "-C", "-", "-o", str(part),
             f"{RELEASE}/{archive.name}"],
            check=True)
        part.replace(archive)

    wanted = set(names)
    with tarfile.open(archive) as tar:
        for member in tar:
            name = Path(member.name).name
            # 필요한 파일만 내용으로 꺼낸다 (압축 안의 경로를 그대로 풀지 않는다).
            if member.isfile() and name in wanted and Path(member.name).parent.name == engine["archive"]:
                with tar.extractfile(member) as source, open(folder / name, "wb") as target:
                    while chunk := source.read(1 << 20):
                        target.write(chunk)
                wanted.discard(name)
    if wanted:
        raise SystemExit(f"{engine['id']}: 압축 파일에 없는 파일: {sorted(wanted)}")
    return folder


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ids", nargs="*")
    parser.add_argument("--threads", type=int, help="모든 모델의 스레드 수를 이 값으로 (기본: 앱 설정)")
    parser.add_argument("--limit", type=int, help="발화를 이만큼만 잰다 (빨리 훑어볼 때)")
    args = parser.parse_args()

    engines = json.loads((HERE / "engines.json").read_text("utf-8"))
    known = {e["id"] for e in engines}
    unknown = [i for i in args.ids if i not in known]
    if unknown:
        raise SystemExit(f"모르는 모델 id: {unknown} (가능: {sorted(known)})")
    chosen = [e for e in engines if (e["id"] in args.ids if args.ids else not e.get("optional"))]

    for engine in chosen:
        folder = ensure(engine)
        engine["bytes"] = sum((folder / name).stat().st_size for name in engine["files"].values())
        if args.threads:
            engine["threads"] = args.threads
        if args.limit:
            engine["limit"] = args.limit
        print(f"{engine['id']}: {engine['bytes'] / 1e6:.0f}MB")
    out = HERE.parent / "build" / "stt" / "engines.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(chosen, ensure_ascii=False, indent=1), "utf-8")
    print(f"{out} — 모델 {len(chosen)}개")


if __name__ == "__main__":
    main()
