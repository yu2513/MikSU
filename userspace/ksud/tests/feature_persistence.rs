#![cfg(unix)]
#![allow(dead_code)]

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

#[path = "../src/feature.rs"]
mod feature;
#[path = "../src/feature_config.rs"]
mod feature_config;
#[path = "../src/feature_id.rs"]
mod feature_id;

mod defs {
    pub const WORKING_DIR: &str = concat!(env!("CARGO_TARGET_TMPDIR"), "/feature-persistence/");
}

mod utils {
    pub fn ensure_dir_exists(path: &std::path::Path) -> anyhow::Result<()> {
        std::fs::create_dir_all(path)?;
        Ok(())
    }
}

mod sulog {
    pub const fn ensure_sulogd_running() -> anyhow::Result<()> {
        Ok(())
    }
}

mod module {
    pub fn get_managed_features() -> anyhow::Result<std::collections::HashMap<String, Vec<String>>>
    {
        Ok(std::collections::HashMap::new())
    }
}

static KERNEL: LazyLock<Mutex<HashMap<u32, ksucalls::FeatureState>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

mod ksucalls {
    #[derive(Clone, Copy, Default)]
    pub struct FeatureState {
        pub supported: bool,
        pub forced: bool,
        pub value: u64,
    }

    pub fn get_feature(id: u32) -> anyhow::Result<FeatureState> {
        super::KERNEL
            .lock()
            .unwrap()
            .get(&id)
            .copied()
            .ok_or_else(|| anyhow::anyhow!("Unknown kernel feature"))
    }

    pub fn set_feature(id: u32, value: u64) -> anyhow::Result<()> {
        super::KERNEL
            .lock()
            .unwrap()
            .get_mut(&id)
            .ok_or_else(|| anyhow::anyhow!("Unknown kernel feature"))?
            .value = value;
        Ok(())
    }
}

#[test]
fn saving_one_feature_preserves_temporary_su_override_and_failed_writes() {
    let initial = HashMap::from([(0, 1), (1, 1), (2, 0), (10003, 0)]);
    feature::save_binary_config(&initial).unwrap();
    for (id, value) in [(0, 0), (1, 1), (2, 1), (10003, 1)] {
        KERNEL.lock().unwrap().insert(
            id,
            ksucalls::FeatureState {
                value,
                supported: true,
                forced: false,
            },
        );
    }
    feature::save_config(Some("avc_spoof")).unwrap();
    let saved = feature::load_binary_config().unwrap();
    assert_eq!(saved, HashMap::from([(0, 1), (1, 1), (2, 0), (10003, 1)]));
    feature::save_config(Some("sulog")).unwrap();
    feature::load_config_and_apply().unwrap();
    assert_eq!(ksucalls::get_feature(0).unwrap().value, 1);
    assert_eq!(ksucalls::get_feature(2).unwrap().value, 1);
    assert_eq!(ksucalls::get_feature(10003).unwrap().value, 1);

    feature::set_persistent_feature("kernel_umount", 0).unwrap();
    assert_eq!(feature::load_binary_config().unwrap()[&1], 0);
    // Model a new kernel boot with its default enabled value.
    ksucalls::set_feature(1, 1).unwrap();
    feature::init_features().unwrap();
    assert_eq!(ksucalls::get_feature(1).unwrap().value, 0);
    assert_eq!(ksucalls::get_feature(0).unwrap().value, 1);
    assert_eq!(ksucalls::get_feature(10003).unwrap().value, 1);

    let path = std::path::Path::new(defs::WORKING_DIR).join(".feature_config");
    let previous = std::fs::read(&path).unwrap();
    KERNEL.lock().unwrap().get_mut(&10003).unwrap().forced = true;
    assert!(feature::save_config(Some("avc_spoof")).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), previous);
    assert!(feature::save_config(Some("removed_feature")).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), previous);

    std::fs::write(&path, b"truncated config").unwrap();
    feature::save_config(Some("sulog")).unwrap();
    assert_eq!(
        feature::load_binary_config().unwrap(),
        HashMap::from([(2, 1)])
    );

    // Publishing failure must restore the runtime value instead of showing
    // a successful disable which disappears on reboot.
    std::fs::remove_file(&path).unwrap();
    std::fs::create_dir(&path).unwrap();
    assert!(feature::set_persistent_feature("kernel_umount", 1).is_err());
    assert_eq!(ksucalls::get_feature(1).unwrap().value, 0);
    std::fs::remove_dir(&path).unwrap();
}
