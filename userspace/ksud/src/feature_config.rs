use anyhow::{Context, Result, bail};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::Path;

#[allow(clippy::unreadable_literal)]
const MAGIC: u32 = 0x7f4b5355;
const VERSION: u32 = 1;

pub fn load(path: &Path) -> Result<HashMap<u32, u64>> {
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(HashMap::new()),
        Err(error) => return Err(error).context("Failed to open feature config"),
    };
    let mut word = [0; 4];
    file.read_exact(&mut word).context("Failed to read magic")?;
    if u32::from_le_bytes(word) != MAGIC {
        bail!("Invalid feature config magic");
    }
    file.read_exact(&mut word)
        .context("Failed to read version")?;
    if u32::from_le_bytes(word) != VERSION {
        log::warn!("Feature config version mismatch");
    }
    file.read_exact(&mut word).context("Failed to read count")?;
    let count = u32::from_le_bytes(word);
    let mut features = HashMap::new();
    for _ in 0..count {
        let mut value = [0; 8];
        file.read_exact(&mut word)
            .context("Failed to read feature id")?;
        file.read_exact(&mut value)
            .context("Failed to read feature value")?;
        features.insert(u32::from_le_bytes(word), u64::from_le_bytes(value));
    }
    Ok(features)
}

pub fn load_for_save(path: &Path) -> HashMap<u32, u64> {
    // Match KernelSU: an invalid old config must not permanently block saving.
    load(path).unwrap_or_else(|error| {
        log::warn!("Cannot load old feature configuration, rebuilding it: {error:#}");
        HashMap::new()
    })
}

pub fn save(path: &Path, features: &HashMap<u32, u64>) -> Result<()> {
    let directory = path.parent().context("Feature config has no parent")?;
    fs::create_dir_all(directory)?;
    let mut file = tempfile::NamedTempFile::new_in(directory)?;
    file.write_all(&MAGIC.to_le_bytes())?;
    file.write_all(&VERSION.to_le_bytes())?;
    file.write_all(&u32::try_from(features.len())?.to_le_bytes())?;
    for (&id, &value) in features {
        file.write_all(&id.to_le_bytes())?;
        file.write_all(&value.to_le_bytes())?;
    }
    file.as_file().sync_all()?;
    file.persist(path)
        .context("Failed to publish feature config")?;
    File::open(directory)?.sync_all()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_umount_survives_reload_and_preserves_other_features() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join(".feature_config");
        let mut features = HashMap::from([(1, 1), (0, 1), (4, 0), (10003, 1), (12345, 7)]);
        save(&path, &features).unwrap();
        features = load_for_save(&path);
        features.insert(1, 0);
        save(&path, &features).unwrap();
        assert_eq!(load(&path).unwrap(), features);
        assert_eq!(load(&path).unwrap()[&1], 0);
        features.insert(1, 1);
        save(&path, &features).unwrap();
        assert_eq!(load(&path).unwrap(), features);
    }

    #[test]
    fn truncated_legacy_config_can_be_replaced_with_a_valid_save() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join(".feature_config");
        fs::write(&path, MAGIC.to_le_bytes()).unwrap();
        assert!(load(&path).is_err());
        let mut features = load_for_save(&path);
        features.insert(1, 0);
        save(&path, &features).unwrap();
        assert_eq!(load(&path).unwrap()[&1], 0);
        assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 1);
    }

    #[test]
    fn upstream_binary_layout_is_unchanged() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join(".feature_config");
        save(&path, &HashMap::from([(1, 0)])).unwrap();
        let expected = [
            MAGIC.to_le_bytes().as_slice(),
            &VERSION.to_le_bytes(),
            &1_u32.to_le_bytes(),
            &1_u32.to_le_bytes(),
            &0_u64.to_le_bytes(),
        ]
        .concat();
        assert_eq!(fs::read(path).unwrap(), expected);
    }
}
