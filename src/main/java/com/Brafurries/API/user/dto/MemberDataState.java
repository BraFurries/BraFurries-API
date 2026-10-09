package com.Brafurries.API.user.dto;

/** Indicates whether a scalar member datum can be read without guessing from legacy rows. */
public enum MemberDataState {
    AVAILABLE,
    NOT_FOUND,
    AMBIGUOUS
}
