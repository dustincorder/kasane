use kasane_connect::protocol::{decode, Decoded, Frame};
use kasane_connect::sas;

fn vectors() -> serde_json::Value {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../docs/connect/test-vectors.json");
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn hex32(s: &str) -> [u8; 32] {
    hex::decode(s).unwrap().try_into().unwrap()
}

#[test]
fn commit_and_sas_match_the_shared_vector() {
    let v = vectors();
    let sas_v = &v["sas"];
    let nc = hex32(sas_v["nc"].as_str().unwrap());
    assert_eq!(hex::encode(sas::commit(&nc)), sas_v["commit"].as_str().unwrap());
    let code = sas::code(
        &hex32(sas_v["fpServer"].as_str().unwrap()),
        &hex32(sas_v["fpClient"].as_str().unwrap()),
        &hex32(sas_v["ns"].as_str().unwrap()),
        &nc,
    );
    assert_eq!(code, sas_v["code"].as_str().unwrap());
}

#[test]
fn all_vector_frames_decode_and_ignore_unknown_fields() {
    let v = vectors();
    for line in v["frames"].as_array().unwrap() {
        let line = line.as_str().unwrap();
        match decode(line) {
            Decoded::Frame(_) => {}
            other => panic!("frame failed to decode: {line} -> {other:?}"),
        }
    }
}

#[test]
fn decoded_command_carries_flattened_args() {
    let v = vectors();
    let line = v["frames"][1].as_str().unwrap();
    let Decoded::Frame(Frame::Command { id, seq, action, args }) = decode(line) else {
        panic!("expected command");
    };
    assert_eq!(id, "c-1");
    assert_eq!(seq, 1);
    assert_eq!(action, "seek");
    assert_eq!(args["positionMs"].as_u64().unwrap(), 61000);
}

#[test]
fn unknown_type_is_reported_not_disconnected() {
    match decode("{\"type\":\"madeUp\",\"x\":1}") {
        Decoded::UnknownType(t) => assert_eq!(t, "madeUp"),
        other => panic!("expected UnknownType, got {other:?}"),
    }
}
