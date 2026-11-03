package com.ownclaw.privacy;

/**
 * Whether a piece of content may leave this machine for a cloud model.
 * <p>
 * Two values, deliberately. The label is derived from facts the code already has — a skill
 * reads a personal-content source (its credentials name one: mail, by default), the task
 * carries an attachment, a parameter references something already private — not from anything
 * a model decides, and not from a taxonomy. A third value would be the beginning of a rule list.
 * A PUBLIC result still goes through the gateway's filter (Redactor), which removes secrets and
 * replaces identifiers wherever they are.
 */
public enum Label {
    PUBLIC, PRIVATE;
}
