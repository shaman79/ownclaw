--liquibase formatted sql

--changeset ownclaw:028-credential-grants-checked
-- From this release a skill is handed a credential only when its user granted that skill that
-- credential (CredentialGrantService): until now /grant and /revoke wrote credential_grants and
-- nothing read it, so a skill was handed every vault value it declared.
--
-- What already works must go on working: the skills are files, not rows, so the pairs in use
-- cannot be granted from here. This row asks for it. The first start that finds it 'pending'
-- grants each registered skill the credentials it declares, to every user whose vault holds
-- them, and writes when it did (DynamicSkillRegistry#init). It happens once: a skill made later,
-- or a credential an existing skill declares later, is granted by its user or not at all. A
-- database with no row asks for nothing.
INSERT INTO system_settings (key, value) VALUES ('credential_grants.existing', 'pending');
