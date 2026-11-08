#![cfg(any(target_os = "android", target_os = "linux"))]

use anyhow::{Context, Result, ensure};
use std::fs::{self, File, OpenOptions};
#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path, PathBuf};

const NOFOLLOW: i32 = 0o400_000;

fn inspect_directories(path: &Path, working_directory: Option<&Path>) -> Result<()> {
    ensure!(path.is_absolute(), "KPM storage path must be absolute");
    let mut current = PathBuf::new();
    for component in path.components() {
        ensure!(
            matches!(component, Component::RootDir | Component::Normal(_)),
            "Invalid KPM storage path"
        );
        current.push(component);
        match fs::symlink_metadata(&current) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() && working_directory == Some(current.as_path())
                {
                    let target = fs::metadata(&current)?;
                    ensure!(
                        target.is_dir() && target.uid() == 0 && target.mode() & 0o022 == 0,
                        "KSU working directory must resolve to a private root-owned directory"
                    );
                } else {
                    ensure!(
                        metadata.is_dir() && !metadata.file_type().is_symlink(),
                        "KPM directory is not a real directory: {}",
                        current.display()
                    );
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

fn lock(path: &Path) -> Result<File> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(NOFOLLOW)
        .open(path)?;
    ensure!(
        file.metadata()?.is_file() && file.metadata()?.nlink() == 1,
        "KPM migration lock is not a private regular file"
    );
    #[cfg(target_os = "android")]
    ensure!(
        unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
        "Another KPM operation is running: {}",
        std::io::Error::last_os_error()
    );
    #[cfg(not(target_os = "android"))]
    file.try_lock()
        .context("Another KPM operation is running")?;
    Ok(file)
}

fn migrate_kpm(source: &Path, destination: &Path) -> Result<()> {
    inspect_directories(source, None)?;
    // KernelSU installations may relocate the working directory via /data/adb/ksu.
    // Only this boundary may be a link; MilkSU's own state remains real directories.
    let working_directory = destination.parent().and_then(Path::parent);
    inspect_directories(destination, working_directory)?;
    let parent = destination
        .parent()
        .context("KPM directory has no parent")?;
    fs::create_dir_all(parent)?;
    fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
    let _migration = lock(&parent.join(".kpm-migration.lock"))?;
    if destination.try_exists()? || !source.try_exists()? {
        return Ok(());
    }
    let previous = source
        .parent()
        .context("Legacy KPM directory has no parent")?;
    ensure!(
        fs::symlink_metadata(previous.join("image-pending.json"))
            .is_err_and(|error| error.kind() == std::io::ErrorKind::NotFound),
        "Complete the previous image recovery before moving KPM data"
    );
    let _registry = lock(&source.join(".lock"))?;
    let integration = source.join("integration");
    let _integration = if integration.try_exists()? {
        Some(lock(&integration.join("operation.lock"))?)
    } else {
        None
    };
    fs::rename(source, destination)
        .context("Move legacy KPM data into the KSU working directory")?;
    File::open(parent)?.sync_all()?;
    File::open(previous)?.sync_all()?;
    match fs::remove_dir(previous) {
        Ok(()) => {}
        Err(error)
            if matches!(
                error.kind(),
                std::io::ErrorKind::NotFound | std::io::ErrorKind::DirectoryNotEmpty
            ) => {}
        Err(error) => return Err(error.into()),
    }
    Ok(())
}

#[cfg(target_os = "android")]
pub fn prepare_kpm() -> Result<()> {
    migrate_kpm(
        Path::new("/data/adb/milksu/kpm"),
        Path::new("/data/adb/ksu/MilkSU/kpm"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;

    #[test]
    fn legacy_kpm_files_move_together_without_changing_contents() {
        let temporary = tempfile::tempdir().unwrap();
        let source = temporary.path().join("adb/milksu/kpm");
        let target = temporary.path().join("adb/ksu/MilkSU/kpm");
        fs::create_dir_all(source.join("integration")).unwrap();
        fs::write(source.join("registry.json"), b"registry").unwrap();
        fs::write(source.join("integration/state_b.json"), b"state").unwrap();
        migrate_kpm(&source, &target).unwrap();
        assert!(!source.exists());
        assert_eq!(fs::read(target.join("registry.json")).unwrap(), b"registry");
        assert_eq!(
            fs::read(target.join("integration/state_b.json")).unwrap(),
            b"state"
        );
        migrate_kpm(&source, &target).unwrap();
    }

    #[test]
    fn current_storage_is_not_replaced_by_legacy_data() {
        let temporary = tempfile::tempdir().unwrap();
        let source = temporary.path().join("old/kpm");
        let target = temporary.path().join("ksu/MilkSU/kpm");
        fs::create_dir_all(&source).unwrap();
        fs::create_dir_all(&target).unwrap();
        fs::write(source.join("registry.json"), b"old").unwrap();
        fs::write(target.join("registry.json"), b"current").unwrap();
        migrate_kpm(&source, &target).unwrap();
        assert_eq!(fs::read(target.join("registry.json")).unwrap(), b"current");
        assert_eq!(fs::read(source.join("registry.json")).unwrap(), b"old");
    }

    #[test]
    fn symlink_ancestors_are_rejected() {
        let temporary = tempfile::tempdir().unwrap();
        let outside = temporary.path().join("outside");
        fs::create_dir(&outside).unwrap();
        symlink(&outside, temporary.path().join("alias")).unwrap();
        assert!(
            migrate_kpm(
                &temporary.path().join("alias/kpm"),
                &temporary.path().join("ksu/MilkSU/kpm")
            )
            .is_err()
        );
        assert!(!temporary.path().join("ksu").exists());
    }

    #[test]
    fn root_owned_ksu_working_directory_link_supports_existing_and_migrated_state() {
        if unsafe { libc::geteuid() } != 0 {
            return;
        }
        let temporary = tempfile::tempdir().unwrap();
        let relocated = temporary.path().join("relocated");
        fs::create_dir(&relocated).unwrap();
        fs::set_permissions(&relocated, fs::Permissions::from_mode(0o700)).unwrap();
        symlink(&relocated, temporary.path().join("ksu")).unwrap();
        let source = temporary.path().join("legacy/kpm");
        let target = temporary.path().join("ksu/MilkSU/kpm");
        fs::create_dir_all(&source).unwrap();
        fs::write(source.join("registry.json"), b"registry").unwrap();
        migrate_kpm(&source, &target).unwrap();
        migrate_kpm(&source, &target).unwrap();
        assert_eq!(
            fs::read(relocated.join("MilkSU/kpm/registry.json")).unwrap(),
            b"registry"
        );
        assert!(
            fs::symlink_metadata(temporary.path().join("ksu"))
                .unwrap()
                .file_type()
                .is_symlink()
        );
    }

    #[test]
    fn writable_working_directory_link_and_linked_milksu_state_are_rejected() {
        let temporary = tempfile::tempdir().unwrap();
        let target = temporary.path().join("relocated");
        fs::create_dir(&target).unwrap();
        fs::set_permissions(&target, fs::Permissions::from_mode(0o777)).unwrap();
        symlink(&target, temporary.path().join("ksu")).unwrap();
        let source = temporary.path().join("legacy/kpm");
        let destination = temporary.path().join("ksu/MilkSU/kpm");
        assert!(migrate_kpm(&source, &destination).is_err());
        fs::set_permissions(&target, fs::Permissions::from_mode(0o700)).unwrap();
        let elsewhere = temporary.path().join("elsewhere");
        fs::create_dir(&elsewhere).unwrap();
        symlink(&elsewhere, target.join("MilkSU")).unwrap();
        assert!(migrate_kpm(&source, &destination).is_err());
        assert_eq!(fs::read_dir(elsewhere).unwrap().count(), 0);
    }

    #[test]
    fn active_registry_blocks_relocation() {
        let temporary = tempfile::tempdir().unwrap();
        let source = temporary.path().join("legacy/kpm");
        let target = temporary.path().join("ksu/MilkSU/kpm");
        fs::create_dir_all(&source).unwrap();
        let _registry = lock(&source.join(".lock")).unwrap();
        assert!(migrate_kpm(&source, &target).is_err());
        assert!(source.is_dir());
        assert!(!target.exists());
    }

    #[test]
    fn unknown_sibling_files_are_preserved() {
        let temporary = tempfile::tempdir().unwrap();
        let source = temporary.path().join("legacy/kpm");
        fs::create_dir_all(&source).unwrap();
        fs::write(source.parent().unwrap().join("keep"), b"user data").unwrap();
        migrate_kpm(&source, &temporary.path().join("ksu/MilkSU/kpm")).unwrap();
        assert_eq!(
            fs::read(source.parent().unwrap().join("keep")).unwrap(),
            b"user data"
        );
    }

    #[test]
    fn unfinished_image_recovery_blocks_migration() {
        let temporary = tempfile::tempdir().unwrap();
        let source = temporary.path().join("legacy/kpm");
        let target = temporary.path().join("ksu/MilkSU/kpm");
        fs::create_dir_all(&source).unwrap();
        fs::write(
            source.parent().unwrap().join("image-pending.json"),
            b"pending",
        )
        .unwrap();
        assert!(migrate_kpm(&source, &target).is_err());
        assert!(source.is_dir());
        assert!(!target.exists());
    }
}
