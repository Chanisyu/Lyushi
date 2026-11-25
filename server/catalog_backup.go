package main

import (
	"archive/zip"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
)

// 每个 SQLite 文件使用在线一致性快照，目录写锁固定账户集合，避免删除与备份竞争。
func snapshotDatabase(source, destination string) error {
	db, err := sql.Open("sqlite", source)
	if err != nil {
		return err
	}
	defer db.Close()
	_, err = db.Exec(`VACUUM INTO ?`, destination)
	return err
}
func (s *Service) backupCatalog(destination string) error {
	if _, err := os.Stat(destination); !os.IsNotExist(err) {
		return errors.New("备份目标必须尚不存在")
	}
	stage, err := os.MkdirTemp(filepath.Dir(destination), ".lyushi-backup-")
	if err != nil {
		return err
	}
	// 暂存目录由本次创建且限制在指定输出父目录；不递归操作任何业务目录。
	base, _ := filepath.Abs(filepath.Dir(destination))
	absolute, _ := filepath.Abs(stage)
	relative, err := filepath.Rel(base, absolute)
	if err != nil || filepath.Dir(relative) != "." {
		return errors.New("备份暂存目录无效")
	}
	defer os.RemoveAll(stage)
	if _, err = s.db.Exec(`BEGIN IMMEDIATE`); err != nil {
		return err
	}
	defer s.db.Exec(`ROLLBACK`)
	if err = snapshotDatabase(s.catalog.path, filepath.Join(stage, "catalog.sqlite")); err != nil {
		return err
	}
	snapshot, err := sql.Open("sqlite", filepath.Join(stage, "catalog.sqlite"))
	if err != nil {
		return err
	}
	rows, err := snapshot.Query(`SELECT id FROM users WHERE deleted=0 ORDER BY id`)
	if err != nil {
		snapshot.Close()
		return err
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			break
		}
		ids = append(ids, id)
	}
	rows.Close()
	snapshot.Close()
	if err != nil {
		return err
	}
	if err = os.Mkdir(filepath.Join(stage, "accounts"), 0700); err != nil {
		return err
	}
	for _, id := range ids {
		store, err := s.userStore(id)
		if err != nil {
			return err
		}
		if err = snapshotDatabase(store.catalog.path, filepath.Join(stage, "accounts", id+".sqlite")); err != nil {
			return err
		}
	}
	manifest, _ := json.Marshal(map[string]any{"format": "lyushi.server.backup", "version": 1, "accounts": ids})
	if err = os.WriteFile(filepath.Join(stage, "manifest.json"), manifest, 0600); err != nil {
		return err
	}
	output, err := os.OpenFile(destination, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	success := false
	defer func() {
		output.Close()
		if !success {
			os.Remove(destination)
		}
	}()
	writer := zip.NewWriter(output)
	names := []string{"catalog.sqlite", "manifest.json"}
	for _, id := range ids {
		names = append(names, "accounts/"+id+".sqlite")
	}
	for _, name := range names {
		source, err := os.Open(filepath.Join(stage, filepath.FromSlash(name)))
		if err != nil {
			return err
		}
		entry, err := writer.Create(name)
		if err != nil {
			source.Close()
			return err
		}
		_, err = io.Copy(entry, source)
		source.Close()
		if err != nil {
			return err
		}
	}
	if err = writer.Close(); err != nil {
		return err
	}
	if err = output.Sync(); err != nil {
		return err
	}
	if err = output.Close(); err != nil {
		return err
	}
	success = true
	return nil
}
