// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'api.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// dart format off
T _$identity<T>(T value) => value;

/// @nodoc
mixin _$BridgeEvent {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is BridgeEvent);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'BridgeEvent()';
  }
}

/// @nodoc
class $BridgeEventCopyWith<$Res> {
  $BridgeEventCopyWith(BridgeEvent _, $Res Function(BridgeEvent) __);
}

/// Adds pattern-matching-related methods to [BridgeEvent].
extension BridgeEventPatterns on BridgeEvent {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(BridgeEvent_IncomingChitthi value)? incomingChitthi,
    TResult Function(BridgeEvent_IncomingDirectMessage value)?
        incomingDirectMessage,
    TResult Function(BridgeEvent_IncomingMedia value)? incomingMedia,
    TResult Function(BridgeEvent_IncomingReaction value)? incomingReaction,
    TResult Function(BridgeEvent_IncomingCallSignal value)? incomingCallSignal,
    TResult Function(BridgeEvent_IncomingMessageRequest value)?
        incomingMessageRequest,
    TResult Function(BridgeEvent_MessageStatus value)? messageStatus,
    TResult Function(BridgeEvent_PeerProfileUpdated value)? peerProfileUpdated,
    TResult Function(BridgeEvent_ComradePresence value)? comradePresence,
    TResult Function(BridgeEvent_ComradeNudge value)? comradeNudge,
    TResult Function(BridgeEvent_RideSignal value)? rideSignal,
    TResult Function(BridgeEvent_TogetherInvited value)? togetherInvited,
    TResult Function(BridgeEvent_TogetherJoined value)? togetherJoined,
    TResult Function(BridgeEvent_TogetherCommand value)? togetherCommand,
    TResult Function(BridgeEvent_TogetherCorrection value)? togetherCorrection,
    TResult Function(BridgeEvent_TogetherEnded value)? togetherEnded,
    TResult Function(BridgeEvent_TogetherShare value)? togetherShare,
    TResult Function(BridgeEvent_TogetherOutbound value)? togetherOutbound,
    TResult Function(BridgeEvent_AttachmentHandoff value)? attachmentHandoff,
    TResult Function(BridgeEvent_MeshStatusChanged value)? meshStatusChanged,
    TResult Function(BridgeEvent_LedgerUpdated value)? ledgerUpdated,
    TResult Function(BridgeEvent_TopicsChanged value)? topicsChanged,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi() when incomingChitthi != null:
        return incomingChitthi(_that);
      case BridgeEvent_IncomingDirectMessage()
          when incomingDirectMessage != null:
        return incomingDirectMessage(_that);
      case BridgeEvent_IncomingMedia() when incomingMedia != null:
        return incomingMedia(_that);
      case BridgeEvent_IncomingReaction() when incomingReaction != null:
        return incomingReaction(_that);
      case BridgeEvent_IncomingCallSignal() when incomingCallSignal != null:
        return incomingCallSignal(_that);
      case BridgeEvent_IncomingMessageRequest()
          when incomingMessageRequest != null:
        return incomingMessageRequest(_that);
      case BridgeEvent_MessageStatus() when messageStatus != null:
        return messageStatus(_that);
      case BridgeEvent_PeerProfileUpdated() when peerProfileUpdated != null:
        return peerProfileUpdated(_that);
      case BridgeEvent_ComradePresence() when comradePresence != null:
        return comradePresence(_that);
      case BridgeEvent_ComradeNudge() when comradeNudge != null:
        return comradeNudge(_that);
      case BridgeEvent_RideSignal() when rideSignal != null:
        return rideSignal(_that);
      case BridgeEvent_TogetherInvited() when togetherInvited != null:
        return togetherInvited(_that);
      case BridgeEvent_TogetherJoined() when togetherJoined != null:
        return togetherJoined(_that);
      case BridgeEvent_TogetherCommand() when togetherCommand != null:
        return togetherCommand(_that);
      case BridgeEvent_TogetherCorrection() when togetherCorrection != null:
        return togetherCorrection(_that);
      case BridgeEvent_TogetherEnded() when togetherEnded != null:
        return togetherEnded(_that);
      case BridgeEvent_TogetherShare() when togetherShare != null:
        return togetherShare(_that);
      case BridgeEvent_TogetherOutbound() when togetherOutbound != null:
        return togetherOutbound(_that);
      case BridgeEvent_AttachmentHandoff() when attachmentHandoff != null:
        return attachmentHandoff(_that);
      case BridgeEvent_MeshStatusChanged() when meshStatusChanged != null:
        return meshStatusChanged(_that);
      case BridgeEvent_LedgerUpdated() when ledgerUpdated != null:
        return ledgerUpdated(_that);
      case BridgeEvent_TopicsChanged() when topicsChanged != null:
        return topicsChanged(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(BridgeEvent_IncomingChitthi value)
        incomingChitthi,
    required TResult Function(BridgeEvent_IncomingDirectMessage value)
        incomingDirectMessage,
    required TResult Function(BridgeEvent_IncomingMedia value) incomingMedia,
    required TResult Function(BridgeEvent_IncomingReaction value)
        incomingReaction,
    required TResult Function(BridgeEvent_IncomingCallSignal value)
        incomingCallSignal,
    required TResult Function(BridgeEvent_IncomingMessageRequest value)
        incomingMessageRequest,
    required TResult Function(BridgeEvent_MessageStatus value) messageStatus,
    required TResult Function(BridgeEvent_PeerProfileUpdated value)
        peerProfileUpdated,
    required TResult Function(BridgeEvent_ComradePresence value)
        comradePresence,
    required TResult Function(BridgeEvent_ComradeNudge value) comradeNudge,
    required TResult Function(BridgeEvent_RideSignal value) rideSignal,
    required TResult Function(BridgeEvent_TogetherInvited value)
        togetherInvited,
    required TResult Function(BridgeEvent_TogetherJoined value) togetherJoined,
    required TResult Function(BridgeEvent_TogetherCommand value)
        togetherCommand,
    required TResult Function(BridgeEvent_TogetherCorrection value)
        togetherCorrection,
    required TResult Function(BridgeEvent_TogetherEnded value) togetherEnded,
    required TResult Function(BridgeEvent_TogetherShare value) togetherShare,
    required TResult Function(BridgeEvent_TogetherOutbound value)
        togetherOutbound,
    required TResult Function(BridgeEvent_AttachmentHandoff value)
        attachmentHandoff,
    required TResult Function(BridgeEvent_MeshStatusChanged value)
        meshStatusChanged,
    required TResult Function(BridgeEvent_LedgerUpdated value) ledgerUpdated,
    required TResult Function(BridgeEvent_TopicsChanged value) topicsChanged,
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi():
        return incomingChitthi(_that);
      case BridgeEvent_IncomingDirectMessage():
        return incomingDirectMessage(_that);
      case BridgeEvent_IncomingMedia():
        return incomingMedia(_that);
      case BridgeEvent_IncomingReaction():
        return incomingReaction(_that);
      case BridgeEvent_IncomingCallSignal():
        return incomingCallSignal(_that);
      case BridgeEvent_IncomingMessageRequest():
        return incomingMessageRequest(_that);
      case BridgeEvent_MessageStatus():
        return messageStatus(_that);
      case BridgeEvent_PeerProfileUpdated():
        return peerProfileUpdated(_that);
      case BridgeEvent_ComradePresence():
        return comradePresence(_that);
      case BridgeEvent_ComradeNudge():
        return comradeNudge(_that);
      case BridgeEvent_RideSignal():
        return rideSignal(_that);
      case BridgeEvent_TogetherInvited():
        return togetherInvited(_that);
      case BridgeEvent_TogetherJoined():
        return togetherJoined(_that);
      case BridgeEvent_TogetherCommand():
        return togetherCommand(_that);
      case BridgeEvent_TogetherCorrection():
        return togetherCorrection(_that);
      case BridgeEvent_TogetherEnded():
        return togetherEnded(_that);
      case BridgeEvent_TogetherShare():
        return togetherShare(_that);
      case BridgeEvent_TogetherOutbound():
        return togetherOutbound(_that);
      case BridgeEvent_AttachmentHandoff():
        return attachmentHandoff(_that);
      case BridgeEvent_MeshStatusChanged():
        return meshStatusChanged(_that);
      case BridgeEvent_LedgerUpdated():
        return ledgerUpdated(_that);
      case BridgeEvent_TopicsChanged():
        return topicsChanged(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(BridgeEvent_IncomingChitthi value)? incomingChitthi,
    TResult? Function(BridgeEvent_IncomingDirectMessage value)?
        incomingDirectMessage,
    TResult? Function(BridgeEvent_IncomingMedia value)? incomingMedia,
    TResult? Function(BridgeEvent_IncomingReaction value)? incomingReaction,
    TResult? Function(BridgeEvent_IncomingCallSignal value)? incomingCallSignal,
    TResult? Function(BridgeEvent_IncomingMessageRequest value)?
        incomingMessageRequest,
    TResult? Function(BridgeEvent_MessageStatus value)? messageStatus,
    TResult? Function(BridgeEvent_PeerProfileUpdated value)? peerProfileUpdated,
    TResult? Function(BridgeEvent_ComradePresence value)? comradePresence,
    TResult? Function(BridgeEvent_ComradeNudge value)? comradeNudge,
    TResult? Function(BridgeEvent_RideSignal value)? rideSignal,
    TResult? Function(BridgeEvent_TogetherInvited value)? togetherInvited,
    TResult? Function(BridgeEvent_TogetherJoined value)? togetherJoined,
    TResult? Function(BridgeEvent_TogetherCommand value)? togetherCommand,
    TResult? Function(BridgeEvent_TogetherCorrection value)? togetherCorrection,
    TResult? Function(BridgeEvent_TogetherEnded value)? togetherEnded,
    TResult? Function(BridgeEvent_TogetherShare value)? togetherShare,
    TResult? Function(BridgeEvent_TogetherOutbound value)? togetherOutbound,
    TResult? Function(BridgeEvent_AttachmentHandoff value)? attachmentHandoff,
    TResult? Function(BridgeEvent_MeshStatusChanged value)? meshStatusChanged,
    TResult? Function(BridgeEvent_LedgerUpdated value)? ledgerUpdated,
    TResult? Function(BridgeEvent_TopicsChanged value)? topicsChanged,
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi() when incomingChitthi != null:
        return incomingChitthi(_that);
      case BridgeEvent_IncomingDirectMessage()
          when incomingDirectMessage != null:
        return incomingDirectMessage(_that);
      case BridgeEvent_IncomingMedia() when incomingMedia != null:
        return incomingMedia(_that);
      case BridgeEvent_IncomingReaction() when incomingReaction != null:
        return incomingReaction(_that);
      case BridgeEvent_IncomingCallSignal() when incomingCallSignal != null:
        return incomingCallSignal(_that);
      case BridgeEvent_IncomingMessageRequest()
          when incomingMessageRequest != null:
        return incomingMessageRequest(_that);
      case BridgeEvent_MessageStatus() when messageStatus != null:
        return messageStatus(_that);
      case BridgeEvent_PeerProfileUpdated() when peerProfileUpdated != null:
        return peerProfileUpdated(_that);
      case BridgeEvent_ComradePresence() when comradePresence != null:
        return comradePresence(_that);
      case BridgeEvent_ComradeNudge() when comradeNudge != null:
        return comradeNudge(_that);
      case BridgeEvent_RideSignal() when rideSignal != null:
        return rideSignal(_that);
      case BridgeEvent_TogetherInvited() when togetherInvited != null:
        return togetherInvited(_that);
      case BridgeEvent_TogetherJoined() when togetherJoined != null:
        return togetherJoined(_that);
      case BridgeEvent_TogetherCommand() when togetherCommand != null:
        return togetherCommand(_that);
      case BridgeEvent_TogetherCorrection() when togetherCorrection != null:
        return togetherCorrection(_that);
      case BridgeEvent_TogetherEnded() when togetherEnded != null:
        return togetherEnded(_that);
      case BridgeEvent_TogetherShare() when togetherShare != null:
        return togetherShare(_that);
      case BridgeEvent_TogetherOutbound() when togetherOutbound != null:
        return togetherOutbound(_that);
      case BridgeEvent_AttachmentHandoff() when attachmentHandoff != null:
        return attachmentHandoff(_that);
      case BridgeEvent_MeshStatusChanged() when meshStatusChanged != null:
        return meshStatusChanged(_that);
      case BridgeEvent_LedgerUpdated() when ledgerUpdated != null:
        return ledgerUpdated(_that);
      case BridgeEvent_TopicsChanged() when topicsChanged != null:
        return topicsChanged(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(ChitthiDto field0)? incomingChitthi,
    TResult Function(DirectMessageDto field0)? incomingDirectMessage,
    TResult Function(MediaMessageDto field0)? incomingMedia,
    TResult Function(ReactionDto field0)? incomingReaction,
    TResult Function(CallSignalDto field0)? incomingCallSignal,
    TResult Function(MessageRequestDto field0)? incomingMessageRequest,
    TResult Function(String peer, List<String> messageIds, String status)?
        messageStatus,
    TResult Function(String peer, String? name)? peerProfileUpdated,
    TResult Function(String peer, String? name, bool online, BigInt at)?
        comradePresence,
    TResult Function(String peer, String? name)? comradeNudge,
    TResult Function(RideSignalDto field0)? rideSignal,
    TResult Function(TogetherInviteDto field0)? togetherInvited,
    TResult Function(String sessionId, String peer)? togetherJoined,
    TResult Function(TogetherCommandDto field0)? togetherCommand,
    TResult Function(TogetherCorrectionDto field0)? togetherCorrection,
    TResult Function(String sessionId, String peer, bool byPeer)? togetherEnded,
    TResult Function(TogetherShareDto field0)? togetherShare,
    TResult Function(String sessionId, String json)? togetherOutbound,
    TResult Function(AttachmentHandoffDto field0)? attachmentHandoff,
    TResult Function(MeshStatusDto field0)? meshStatusChanged,
    TResult Function(String ledger)? ledgerUpdated,
    TResult Function(String peer)? topicsChanged,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi() when incomingChitthi != null:
        return incomingChitthi(_that.field0);
      case BridgeEvent_IncomingDirectMessage()
          when incomingDirectMessage != null:
        return incomingDirectMessage(_that.field0);
      case BridgeEvent_IncomingMedia() when incomingMedia != null:
        return incomingMedia(_that.field0);
      case BridgeEvent_IncomingReaction() when incomingReaction != null:
        return incomingReaction(_that.field0);
      case BridgeEvent_IncomingCallSignal() when incomingCallSignal != null:
        return incomingCallSignal(_that.field0);
      case BridgeEvent_IncomingMessageRequest()
          when incomingMessageRequest != null:
        return incomingMessageRequest(_that.field0);
      case BridgeEvent_MessageStatus() when messageStatus != null:
        return messageStatus(_that.peer, _that.messageIds, _that.status);
      case BridgeEvent_PeerProfileUpdated() when peerProfileUpdated != null:
        return peerProfileUpdated(_that.peer, _that.name);
      case BridgeEvent_ComradePresence() when comradePresence != null:
        return comradePresence(_that.peer, _that.name, _that.online, _that.at);
      case BridgeEvent_ComradeNudge() when comradeNudge != null:
        return comradeNudge(_that.peer, _that.name);
      case BridgeEvent_RideSignal() when rideSignal != null:
        return rideSignal(_that.field0);
      case BridgeEvent_TogetherInvited() when togetherInvited != null:
        return togetherInvited(_that.field0);
      case BridgeEvent_TogetherJoined() when togetherJoined != null:
        return togetherJoined(_that.sessionId, _that.peer);
      case BridgeEvent_TogetherCommand() when togetherCommand != null:
        return togetherCommand(_that.field0);
      case BridgeEvent_TogetherCorrection() when togetherCorrection != null:
        return togetherCorrection(_that.field0);
      case BridgeEvent_TogetherEnded() when togetherEnded != null:
        return togetherEnded(_that.sessionId, _that.peer, _that.byPeer);
      case BridgeEvent_TogetherShare() when togetherShare != null:
        return togetherShare(_that.field0);
      case BridgeEvent_TogetherOutbound() when togetherOutbound != null:
        return togetherOutbound(_that.sessionId, _that.json);
      case BridgeEvent_AttachmentHandoff() when attachmentHandoff != null:
        return attachmentHandoff(_that.field0);
      case BridgeEvent_MeshStatusChanged() when meshStatusChanged != null:
        return meshStatusChanged(_that.field0);
      case BridgeEvent_LedgerUpdated() when ledgerUpdated != null:
        return ledgerUpdated(_that.ledger);
      case BridgeEvent_TopicsChanged() when topicsChanged != null:
        return topicsChanged(_that.peer);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(ChitthiDto field0) incomingChitthi,
    required TResult Function(DirectMessageDto field0) incomingDirectMessage,
    required TResult Function(MediaMessageDto field0) incomingMedia,
    required TResult Function(ReactionDto field0) incomingReaction,
    required TResult Function(CallSignalDto field0) incomingCallSignal,
    required TResult Function(MessageRequestDto field0) incomingMessageRequest,
    required TResult Function(
            String peer, List<String> messageIds, String status)
        messageStatus,
    required TResult Function(String peer, String? name) peerProfileUpdated,
    required TResult Function(String peer, String? name, bool online, BigInt at)
        comradePresence,
    required TResult Function(String peer, String? name) comradeNudge,
    required TResult Function(RideSignalDto field0) rideSignal,
    required TResult Function(TogetherInviteDto field0) togetherInvited,
    required TResult Function(String sessionId, String peer) togetherJoined,
    required TResult Function(TogetherCommandDto field0) togetherCommand,
    required TResult Function(TogetherCorrectionDto field0) togetherCorrection,
    required TResult Function(String sessionId, String peer, bool byPeer)
        togetherEnded,
    required TResult Function(TogetherShareDto field0) togetherShare,
    required TResult Function(String sessionId, String json) togetherOutbound,
    required TResult Function(AttachmentHandoffDto field0) attachmentHandoff,
    required TResult Function(MeshStatusDto field0) meshStatusChanged,
    required TResult Function(String ledger) ledgerUpdated,
    required TResult Function(String peer) topicsChanged,
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi():
        return incomingChitthi(_that.field0);
      case BridgeEvent_IncomingDirectMessage():
        return incomingDirectMessage(_that.field0);
      case BridgeEvent_IncomingMedia():
        return incomingMedia(_that.field0);
      case BridgeEvent_IncomingReaction():
        return incomingReaction(_that.field0);
      case BridgeEvent_IncomingCallSignal():
        return incomingCallSignal(_that.field0);
      case BridgeEvent_IncomingMessageRequest():
        return incomingMessageRequest(_that.field0);
      case BridgeEvent_MessageStatus():
        return messageStatus(_that.peer, _that.messageIds, _that.status);
      case BridgeEvent_PeerProfileUpdated():
        return peerProfileUpdated(_that.peer, _that.name);
      case BridgeEvent_ComradePresence():
        return comradePresence(_that.peer, _that.name, _that.online, _that.at);
      case BridgeEvent_ComradeNudge():
        return comradeNudge(_that.peer, _that.name);
      case BridgeEvent_RideSignal():
        return rideSignal(_that.field0);
      case BridgeEvent_TogetherInvited():
        return togetherInvited(_that.field0);
      case BridgeEvent_TogetherJoined():
        return togetherJoined(_that.sessionId, _that.peer);
      case BridgeEvent_TogetherCommand():
        return togetherCommand(_that.field0);
      case BridgeEvent_TogetherCorrection():
        return togetherCorrection(_that.field0);
      case BridgeEvent_TogetherEnded():
        return togetherEnded(_that.sessionId, _that.peer, _that.byPeer);
      case BridgeEvent_TogetherShare():
        return togetherShare(_that.field0);
      case BridgeEvent_TogetherOutbound():
        return togetherOutbound(_that.sessionId, _that.json);
      case BridgeEvent_AttachmentHandoff():
        return attachmentHandoff(_that.field0);
      case BridgeEvent_MeshStatusChanged():
        return meshStatusChanged(_that.field0);
      case BridgeEvent_LedgerUpdated():
        return ledgerUpdated(_that.ledger);
      case BridgeEvent_TopicsChanged():
        return topicsChanged(_that.peer);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(ChitthiDto field0)? incomingChitthi,
    TResult? Function(DirectMessageDto field0)? incomingDirectMessage,
    TResult? Function(MediaMessageDto field0)? incomingMedia,
    TResult? Function(ReactionDto field0)? incomingReaction,
    TResult? Function(CallSignalDto field0)? incomingCallSignal,
    TResult? Function(MessageRequestDto field0)? incomingMessageRequest,
    TResult? Function(String peer, List<String> messageIds, String status)?
        messageStatus,
    TResult? Function(String peer, String? name)? peerProfileUpdated,
    TResult? Function(String peer, String? name, bool online, BigInt at)?
        comradePresence,
    TResult? Function(String peer, String? name)? comradeNudge,
    TResult? Function(RideSignalDto field0)? rideSignal,
    TResult? Function(TogetherInviteDto field0)? togetherInvited,
    TResult? Function(String sessionId, String peer)? togetherJoined,
    TResult? Function(TogetherCommandDto field0)? togetherCommand,
    TResult? Function(TogetherCorrectionDto field0)? togetherCorrection,
    TResult? Function(String sessionId, String peer, bool byPeer)?
        togetherEnded,
    TResult? Function(TogetherShareDto field0)? togetherShare,
    TResult? Function(String sessionId, String json)? togetherOutbound,
    TResult? Function(AttachmentHandoffDto field0)? attachmentHandoff,
    TResult? Function(MeshStatusDto field0)? meshStatusChanged,
    TResult? Function(String ledger)? ledgerUpdated,
    TResult? Function(String peer)? topicsChanged,
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi() when incomingChitthi != null:
        return incomingChitthi(_that.field0);
      case BridgeEvent_IncomingDirectMessage()
          when incomingDirectMessage != null:
        return incomingDirectMessage(_that.field0);
      case BridgeEvent_IncomingMedia() when incomingMedia != null:
        return incomingMedia(_that.field0);
      case BridgeEvent_IncomingReaction() when incomingReaction != null:
        return incomingReaction(_that.field0);
      case BridgeEvent_IncomingCallSignal() when incomingCallSignal != null:
        return incomingCallSignal(_that.field0);
      case BridgeEvent_IncomingMessageRequest()
          when incomingMessageRequest != null:
        return incomingMessageRequest(_that.field0);
      case BridgeEvent_MessageStatus() when messageStatus != null:
        return messageStatus(_that.peer, _that.messageIds, _that.status);
      case BridgeEvent_PeerProfileUpdated() when peerProfileUpdated != null:
        return peerProfileUpdated(_that.peer, _that.name);
      case BridgeEvent_ComradePresence() when comradePresence != null:
        return comradePresence(_that.peer, _that.name, _that.online, _that.at);
      case BridgeEvent_ComradeNudge() when comradeNudge != null:
        return comradeNudge(_that.peer, _that.name);
      case BridgeEvent_RideSignal() when rideSignal != null:
        return rideSignal(_that.field0);
      case BridgeEvent_TogetherInvited() when togetherInvited != null:
        return togetherInvited(_that.field0);
      case BridgeEvent_TogetherJoined() when togetherJoined != null:
        return togetherJoined(_that.sessionId, _that.peer);
      case BridgeEvent_TogetherCommand() when togetherCommand != null:
        return togetherCommand(_that.field0);
      case BridgeEvent_TogetherCorrection() when togetherCorrection != null:
        return togetherCorrection(_that.field0);
      case BridgeEvent_TogetherEnded() when togetherEnded != null:
        return togetherEnded(_that.sessionId, _that.peer, _that.byPeer);
      case BridgeEvent_TogetherShare() when togetherShare != null:
        return togetherShare(_that.field0);
      case BridgeEvent_TogetherOutbound() when togetherOutbound != null:
        return togetherOutbound(_that.sessionId, _that.json);
      case BridgeEvent_AttachmentHandoff() when attachmentHandoff != null:
        return attachmentHandoff(_that.field0);
      case BridgeEvent_MeshStatusChanged() when meshStatusChanged != null:
        return meshStatusChanged(_that.field0);
      case BridgeEvent_LedgerUpdated() when ledgerUpdated != null:
        return ledgerUpdated(_that.ledger);
      case BridgeEvent_TopicsChanged() when topicsChanged != null:
        return topicsChanged(_that.peer);
      case _:
        return null;
    }
  }
}

/// @nodoc

class BridgeEvent_IncomingChitthi extends BridgeEvent {
  const BridgeEvent_IncomingChitthi(this.field0) : super._();

  final ChitthiDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingChitthiCopyWith<BridgeEvent_IncomingChitthi>
      get copyWith => _$BridgeEvent_IncomingChitthiCopyWithImpl<
          BridgeEvent_IncomingChitthi>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingChitthi &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingChitthi(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingChitthiCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingChitthiCopyWith(
          BridgeEvent_IncomingChitthi value,
          $Res Function(BridgeEvent_IncomingChitthi) _then) =
      _$BridgeEvent_IncomingChitthiCopyWithImpl;
  @useResult
  $Res call({ChitthiDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingChitthiCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingChitthiCopyWith<$Res> {
  _$BridgeEvent_IncomingChitthiCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingChitthi _self;
  final $Res Function(BridgeEvent_IncomingChitthi) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingChitthi(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as ChitthiDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingDirectMessage extends BridgeEvent {
  const BridgeEvent_IncomingDirectMessage(this.field0) : super._();

  final DirectMessageDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingDirectMessageCopyWith<BridgeEvent_IncomingDirectMessage>
      get copyWith => _$BridgeEvent_IncomingDirectMessageCopyWithImpl<
          BridgeEvent_IncomingDirectMessage>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingDirectMessage &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingDirectMessage(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingDirectMessageCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingDirectMessageCopyWith(
          BridgeEvent_IncomingDirectMessage value,
          $Res Function(BridgeEvent_IncomingDirectMessage) _then) =
      _$BridgeEvent_IncomingDirectMessageCopyWithImpl;
  @useResult
  $Res call({DirectMessageDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingDirectMessageCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingDirectMessageCopyWith<$Res> {
  _$BridgeEvent_IncomingDirectMessageCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingDirectMessage _self;
  final $Res Function(BridgeEvent_IncomingDirectMessage) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingDirectMessage(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as DirectMessageDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingMedia extends BridgeEvent {
  const BridgeEvent_IncomingMedia(this.field0) : super._();

  final MediaMessageDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingMediaCopyWith<BridgeEvent_IncomingMedia> get copyWith =>
      _$BridgeEvent_IncomingMediaCopyWithImpl<BridgeEvent_IncomingMedia>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingMedia &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingMedia(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingMediaCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingMediaCopyWith(BridgeEvent_IncomingMedia value,
          $Res Function(BridgeEvent_IncomingMedia) _then) =
      _$BridgeEvent_IncomingMediaCopyWithImpl;
  @useResult
  $Res call({MediaMessageDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingMediaCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingMediaCopyWith<$Res> {
  _$BridgeEvent_IncomingMediaCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingMedia _self;
  final $Res Function(BridgeEvent_IncomingMedia) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingMedia(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as MediaMessageDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingReaction extends BridgeEvent {
  const BridgeEvent_IncomingReaction(this.field0) : super._();

  final ReactionDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingReactionCopyWith<BridgeEvent_IncomingReaction>
      get copyWith => _$BridgeEvent_IncomingReactionCopyWithImpl<
          BridgeEvent_IncomingReaction>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingReaction &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingReaction(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingReactionCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingReactionCopyWith(
          BridgeEvent_IncomingReaction value,
          $Res Function(BridgeEvent_IncomingReaction) _then) =
      _$BridgeEvent_IncomingReactionCopyWithImpl;
  @useResult
  $Res call({ReactionDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingReactionCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingReactionCopyWith<$Res> {
  _$BridgeEvent_IncomingReactionCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingReaction _self;
  final $Res Function(BridgeEvent_IncomingReaction) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingReaction(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as ReactionDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingCallSignal extends BridgeEvent {
  const BridgeEvent_IncomingCallSignal(this.field0) : super._();

  final CallSignalDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingCallSignalCopyWith<BridgeEvent_IncomingCallSignal>
      get copyWith => _$BridgeEvent_IncomingCallSignalCopyWithImpl<
          BridgeEvent_IncomingCallSignal>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingCallSignal &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingCallSignal(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingCallSignalCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingCallSignalCopyWith(
          BridgeEvent_IncomingCallSignal value,
          $Res Function(BridgeEvent_IncomingCallSignal) _then) =
      _$BridgeEvent_IncomingCallSignalCopyWithImpl;
  @useResult
  $Res call({CallSignalDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingCallSignalCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingCallSignalCopyWith<$Res> {
  _$BridgeEvent_IncomingCallSignalCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingCallSignal _self;
  final $Res Function(BridgeEvent_IncomingCallSignal) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingCallSignal(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as CallSignalDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingMessageRequest extends BridgeEvent {
  const BridgeEvent_IncomingMessageRequest(this.field0) : super._();

  final MessageRequestDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingMessageRequestCopyWith<
          BridgeEvent_IncomingMessageRequest>
      get copyWith => _$BridgeEvent_IncomingMessageRequestCopyWithImpl<
          BridgeEvent_IncomingMessageRequest>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingMessageRequest &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingMessageRequest(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingMessageRequestCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingMessageRequestCopyWith(
          BridgeEvent_IncomingMessageRequest value,
          $Res Function(BridgeEvent_IncomingMessageRequest) _then) =
      _$BridgeEvent_IncomingMessageRequestCopyWithImpl;
  @useResult
  $Res call({MessageRequestDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingMessageRequestCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingMessageRequestCopyWith<$Res> {
  _$BridgeEvent_IncomingMessageRequestCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingMessageRequest _self;
  final $Res Function(BridgeEvent_IncomingMessageRequest) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingMessageRequest(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as MessageRequestDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_MessageStatus extends BridgeEvent {
  const BridgeEvent_MessageStatus(
      {required this.peer,
      required final List<String> messageIds,
      required this.status})
      : _messageIds = messageIds,
        super._();

  final String peer;
  final List<String> _messageIds;
  List<String> get messageIds {
    if (_messageIds is EqualUnmodifiableListView) return _messageIds;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_messageIds);
  }

  final String status;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_MessageStatusCopyWith<BridgeEvent_MessageStatus> get copyWith =>
      _$BridgeEvent_MessageStatusCopyWithImpl<BridgeEvent_MessageStatus>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_MessageStatus &&
            (identical(other.peer, peer) || other.peer == peer) &&
            const DeepCollectionEquality()
                .equals(other._messageIds, _messageIds) &&
            (identical(other.status, status) || other.status == status));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer,
      const DeepCollectionEquality().hash(_messageIds), status);

  @override
  String toString() {
    return 'BridgeEvent.messageStatus(peer: $peer, messageIds: $messageIds, status: $status)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_MessageStatusCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_MessageStatusCopyWith(BridgeEvent_MessageStatus value,
          $Res Function(BridgeEvent_MessageStatus) _then) =
      _$BridgeEvent_MessageStatusCopyWithImpl;
  @useResult
  $Res call({String peer, List<String> messageIds, String status});
}

/// @nodoc
class _$BridgeEvent_MessageStatusCopyWithImpl<$Res>
    implements $BridgeEvent_MessageStatusCopyWith<$Res> {
  _$BridgeEvent_MessageStatusCopyWithImpl(this._self, this._then);

  final BridgeEvent_MessageStatus _self;
  final $Res Function(BridgeEvent_MessageStatus) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
    Object? messageIds = null,
    Object? status = null,
  }) {
    return _then(BridgeEvent_MessageStatus(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      messageIds: null == messageIds
          ? _self._messageIds
          : messageIds // ignore: cast_nullable_to_non_nullable
              as List<String>,
      status: null == status
          ? _self.status
          : status // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class BridgeEvent_PeerProfileUpdated extends BridgeEvent {
  const BridgeEvent_PeerProfileUpdated({required this.peer, this.name})
      : super._();

  final String peer;
  final String? name;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_PeerProfileUpdatedCopyWith<BridgeEvent_PeerProfileUpdated>
      get copyWith => _$BridgeEvent_PeerProfileUpdatedCopyWithImpl<
          BridgeEvent_PeerProfileUpdated>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_PeerProfileUpdated &&
            (identical(other.peer, peer) || other.peer == peer) &&
            (identical(other.name, name) || other.name == name));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer, name);

  @override
  String toString() {
    return 'BridgeEvent.peerProfileUpdated(peer: $peer, name: $name)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_PeerProfileUpdatedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_PeerProfileUpdatedCopyWith(
          BridgeEvent_PeerProfileUpdated value,
          $Res Function(BridgeEvent_PeerProfileUpdated) _then) =
      _$BridgeEvent_PeerProfileUpdatedCopyWithImpl;
  @useResult
  $Res call({String peer, String? name});
}

/// @nodoc
class _$BridgeEvent_PeerProfileUpdatedCopyWithImpl<$Res>
    implements $BridgeEvent_PeerProfileUpdatedCopyWith<$Res> {
  _$BridgeEvent_PeerProfileUpdatedCopyWithImpl(this._self, this._then);

  final BridgeEvent_PeerProfileUpdated _self;
  final $Res Function(BridgeEvent_PeerProfileUpdated) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
    Object? name = freezed,
  }) {
    return _then(BridgeEvent_PeerProfileUpdated(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      name: freezed == name
          ? _self.name
          : name // ignore: cast_nullable_to_non_nullable
              as String?,
    ));
  }
}

/// @nodoc

class BridgeEvent_ComradePresence extends BridgeEvent {
  const BridgeEvent_ComradePresence(
      {required this.peer, this.name, required this.online, required this.at})
      : super._();

  final String peer;
  final String? name;
  final bool online;
  final BigInt at;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_ComradePresenceCopyWith<BridgeEvent_ComradePresence>
      get copyWith => _$BridgeEvent_ComradePresenceCopyWithImpl<
          BridgeEvent_ComradePresence>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_ComradePresence &&
            (identical(other.peer, peer) || other.peer == peer) &&
            (identical(other.name, name) || other.name == name) &&
            (identical(other.online, online) || other.online == online) &&
            (identical(other.at, at) || other.at == at));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer, name, online, at);

  @override
  String toString() {
    return 'BridgeEvent.comradePresence(peer: $peer, name: $name, online: $online, at: $at)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_ComradePresenceCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_ComradePresenceCopyWith(
          BridgeEvent_ComradePresence value,
          $Res Function(BridgeEvent_ComradePresence) _then) =
      _$BridgeEvent_ComradePresenceCopyWithImpl;
  @useResult
  $Res call({String peer, String? name, bool online, BigInt at});
}

/// @nodoc
class _$BridgeEvent_ComradePresenceCopyWithImpl<$Res>
    implements $BridgeEvent_ComradePresenceCopyWith<$Res> {
  _$BridgeEvent_ComradePresenceCopyWithImpl(this._self, this._then);

  final BridgeEvent_ComradePresence _self;
  final $Res Function(BridgeEvent_ComradePresence) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
    Object? name = freezed,
    Object? online = null,
    Object? at = null,
  }) {
    return _then(BridgeEvent_ComradePresence(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      name: freezed == name
          ? _self.name
          : name // ignore: cast_nullable_to_non_nullable
              as String?,
      online: null == online
          ? _self.online
          : online // ignore: cast_nullable_to_non_nullable
              as bool,
      at: null == at
          ? _self.at
          : at // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc

class BridgeEvent_ComradeNudge extends BridgeEvent {
  const BridgeEvent_ComradeNudge({required this.peer, this.name}) : super._();

  final String peer;
  final String? name;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_ComradeNudgeCopyWith<BridgeEvent_ComradeNudge> get copyWith =>
      _$BridgeEvent_ComradeNudgeCopyWithImpl<BridgeEvent_ComradeNudge>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_ComradeNudge &&
            (identical(other.peer, peer) || other.peer == peer) &&
            (identical(other.name, name) || other.name == name));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer, name);

  @override
  String toString() {
    return 'BridgeEvent.comradeNudge(peer: $peer, name: $name)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_ComradeNudgeCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_ComradeNudgeCopyWith(BridgeEvent_ComradeNudge value,
          $Res Function(BridgeEvent_ComradeNudge) _then) =
      _$BridgeEvent_ComradeNudgeCopyWithImpl;
  @useResult
  $Res call({String peer, String? name});
}

/// @nodoc
class _$BridgeEvent_ComradeNudgeCopyWithImpl<$Res>
    implements $BridgeEvent_ComradeNudgeCopyWith<$Res> {
  _$BridgeEvent_ComradeNudgeCopyWithImpl(this._self, this._then);

  final BridgeEvent_ComradeNudge _self;
  final $Res Function(BridgeEvent_ComradeNudge) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
    Object? name = freezed,
  }) {
    return _then(BridgeEvent_ComradeNudge(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      name: freezed == name
          ? _self.name
          : name // ignore: cast_nullable_to_non_nullable
              as String?,
    ));
  }
}

/// @nodoc

class BridgeEvent_RideSignal extends BridgeEvent {
  const BridgeEvent_RideSignal(this.field0) : super._();

  final RideSignalDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_RideSignalCopyWith<BridgeEvent_RideSignal> get copyWith =>
      _$BridgeEvent_RideSignalCopyWithImpl<BridgeEvent_RideSignal>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_RideSignal &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.rideSignal(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_RideSignalCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_RideSignalCopyWith(BridgeEvent_RideSignal value,
          $Res Function(BridgeEvent_RideSignal) _then) =
      _$BridgeEvent_RideSignalCopyWithImpl;
  @useResult
  $Res call({RideSignalDto field0});
}

/// @nodoc
class _$BridgeEvent_RideSignalCopyWithImpl<$Res>
    implements $BridgeEvent_RideSignalCopyWith<$Res> {
  _$BridgeEvent_RideSignalCopyWithImpl(this._self, this._then);

  final BridgeEvent_RideSignal _self;
  final $Res Function(BridgeEvent_RideSignal) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_RideSignal(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as RideSignalDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherInvited extends BridgeEvent {
  const BridgeEvent_TogetherInvited(this.field0) : super._();

  final TogetherInviteDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherInvitedCopyWith<BridgeEvent_TogetherInvited>
      get copyWith => _$BridgeEvent_TogetherInvitedCopyWithImpl<
          BridgeEvent_TogetherInvited>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherInvited &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.togetherInvited(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherInvitedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherInvitedCopyWith(
          BridgeEvent_TogetherInvited value,
          $Res Function(BridgeEvent_TogetherInvited) _then) =
      _$BridgeEvent_TogetherInvitedCopyWithImpl;
  @useResult
  $Res call({TogetherInviteDto field0});
}

/// @nodoc
class _$BridgeEvent_TogetherInvitedCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherInvitedCopyWith<$Res> {
  _$BridgeEvent_TogetherInvitedCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherInvited _self;
  final $Res Function(BridgeEvent_TogetherInvited) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_TogetherInvited(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as TogetherInviteDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherJoined extends BridgeEvent {
  const BridgeEvent_TogetherJoined(
      {required this.sessionId, required this.peer})
      : super._();

  final String sessionId;
  final String peer;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherJoinedCopyWith<BridgeEvent_TogetherJoined>
      get copyWith =>
          _$BridgeEvent_TogetherJoinedCopyWithImpl<BridgeEvent_TogetherJoined>(
              this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherJoined &&
            (identical(other.sessionId, sessionId) ||
                other.sessionId == sessionId) &&
            (identical(other.peer, peer) || other.peer == peer));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sessionId, peer);

  @override
  String toString() {
    return 'BridgeEvent.togetherJoined(sessionId: $sessionId, peer: $peer)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherJoinedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherJoinedCopyWith(BridgeEvent_TogetherJoined value,
          $Res Function(BridgeEvent_TogetherJoined) _then) =
      _$BridgeEvent_TogetherJoinedCopyWithImpl;
  @useResult
  $Res call({String sessionId, String peer});
}

/// @nodoc
class _$BridgeEvent_TogetherJoinedCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherJoinedCopyWith<$Res> {
  _$BridgeEvent_TogetherJoinedCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherJoined _self;
  final $Res Function(BridgeEvent_TogetherJoined) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sessionId = null,
    Object? peer = null,
  }) {
    return _then(BridgeEvent_TogetherJoined(
      sessionId: null == sessionId
          ? _self.sessionId
          : sessionId // ignore: cast_nullable_to_non_nullable
              as String,
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherCommand extends BridgeEvent {
  const BridgeEvent_TogetherCommand(this.field0) : super._();

  final TogetherCommandDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherCommandCopyWith<BridgeEvent_TogetherCommand>
      get copyWith => _$BridgeEvent_TogetherCommandCopyWithImpl<
          BridgeEvent_TogetherCommand>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherCommand &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.togetherCommand(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherCommandCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherCommandCopyWith(
          BridgeEvent_TogetherCommand value,
          $Res Function(BridgeEvent_TogetherCommand) _then) =
      _$BridgeEvent_TogetherCommandCopyWithImpl;
  @useResult
  $Res call({TogetherCommandDto field0});
}

/// @nodoc
class _$BridgeEvent_TogetherCommandCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherCommandCopyWith<$Res> {
  _$BridgeEvent_TogetherCommandCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherCommand _self;
  final $Res Function(BridgeEvent_TogetherCommand) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_TogetherCommand(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as TogetherCommandDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherCorrection extends BridgeEvent {
  const BridgeEvent_TogetherCorrection(this.field0) : super._();

  final TogetherCorrectionDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherCorrectionCopyWith<BridgeEvent_TogetherCorrection>
      get copyWith => _$BridgeEvent_TogetherCorrectionCopyWithImpl<
          BridgeEvent_TogetherCorrection>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherCorrection &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.togetherCorrection(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherCorrectionCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherCorrectionCopyWith(
          BridgeEvent_TogetherCorrection value,
          $Res Function(BridgeEvent_TogetherCorrection) _then) =
      _$BridgeEvent_TogetherCorrectionCopyWithImpl;
  @useResult
  $Res call({TogetherCorrectionDto field0});
}

/// @nodoc
class _$BridgeEvent_TogetherCorrectionCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherCorrectionCopyWith<$Res> {
  _$BridgeEvent_TogetherCorrectionCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherCorrection _self;
  final $Res Function(BridgeEvent_TogetherCorrection) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_TogetherCorrection(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as TogetherCorrectionDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherEnded extends BridgeEvent {
  const BridgeEvent_TogetherEnded(
      {required this.sessionId, required this.peer, required this.byPeer})
      : super._();

  final String sessionId;
  final String peer;
  final bool byPeer;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherEndedCopyWith<BridgeEvent_TogetherEnded> get copyWith =>
      _$BridgeEvent_TogetherEndedCopyWithImpl<BridgeEvent_TogetherEnded>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherEnded &&
            (identical(other.sessionId, sessionId) ||
                other.sessionId == sessionId) &&
            (identical(other.peer, peer) || other.peer == peer) &&
            (identical(other.byPeer, byPeer) || other.byPeer == byPeer));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sessionId, peer, byPeer);

  @override
  String toString() {
    return 'BridgeEvent.togetherEnded(sessionId: $sessionId, peer: $peer, byPeer: $byPeer)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherEndedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherEndedCopyWith(BridgeEvent_TogetherEnded value,
          $Res Function(BridgeEvent_TogetherEnded) _then) =
      _$BridgeEvent_TogetherEndedCopyWithImpl;
  @useResult
  $Res call({String sessionId, String peer, bool byPeer});
}

/// @nodoc
class _$BridgeEvent_TogetherEndedCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherEndedCopyWith<$Res> {
  _$BridgeEvent_TogetherEndedCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherEnded _self;
  final $Res Function(BridgeEvent_TogetherEnded) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sessionId = null,
    Object? peer = null,
    Object? byPeer = null,
  }) {
    return _then(BridgeEvent_TogetherEnded(
      sessionId: null == sessionId
          ? _self.sessionId
          : sessionId // ignore: cast_nullable_to_non_nullable
              as String,
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      byPeer: null == byPeer
          ? _self.byPeer
          : byPeer // ignore: cast_nullable_to_non_nullable
              as bool,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherShare extends BridgeEvent {
  const BridgeEvent_TogetherShare(this.field0) : super._();

  final TogetherShareDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherShareCopyWith<BridgeEvent_TogetherShare> get copyWith =>
      _$BridgeEvent_TogetherShareCopyWithImpl<BridgeEvent_TogetherShare>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherShare &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.togetherShare(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherShareCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherShareCopyWith(BridgeEvent_TogetherShare value,
          $Res Function(BridgeEvent_TogetherShare) _then) =
      _$BridgeEvent_TogetherShareCopyWithImpl;
  @useResult
  $Res call({TogetherShareDto field0});
}

/// @nodoc
class _$BridgeEvent_TogetherShareCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherShareCopyWith<$Res> {
  _$BridgeEvent_TogetherShareCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherShare _self;
  final $Res Function(BridgeEvent_TogetherShare) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_TogetherShare(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as TogetherShareDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherOutbound extends BridgeEvent {
  const BridgeEvent_TogetherOutbound(
      {required this.sessionId, required this.json})
      : super._();

  final String sessionId;
  final String json;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherOutboundCopyWith<BridgeEvent_TogetherOutbound>
      get copyWith => _$BridgeEvent_TogetherOutboundCopyWithImpl<
          BridgeEvent_TogetherOutbound>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherOutbound &&
            (identical(other.sessionId, sessionId) ||
                other.sessionId == sessionId) &&
            (identical(other.json, json) || other.json == json));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sessionId, json);

  @override
  String toString() {
    return 'BridgeEvent.togetherOutbound(sessionId: $sessionId, json: $json)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherOutboundCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherOutboundCopyWith(
          BridgeEvent_TogetherOutbound value,
          $Res Function(BridgeEvent_TogetherOutbound) _then) =
      _$BridgeEvent_TogetherOutboundCopyWithImpl;
  @useResult
  $Res call({String sessionId, String json});
}

/// @nodoc
class _$BridgeEvent_TogetherOutboundCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherOutboundCopyWith<$Res> {
  _$BridgeEvent_TogetherOutboundCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherOutbound _self;
  final $Res Function(BridgeEvent_TogetherOutbound) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sessionId = null,
    Object? json = null,
  }) {
    return _then(BridgeEvent_TogetherOutbound(
      sessionId: null == sessionId
          ? _self.sessionId
          : sessionId // ignore: cast_nullable_to_non_nullable
              as String,
      json: null == json
          ? _self.json
          : json // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class BridgeEvent_AttachmentHandoff extends BridgeEvent {
  const BridgeEvent_AttachmentHandoff(this.field0) : super._();

  final AttachmentHandoffDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_AttachmentHandoffCopyWith<BridgeEvent_AttachmentHandoff>
      get copyWith => _$BridgeEvent_AttachmentHandoffCopyWithImpl<
          BridgeEvent_AttachmentHandoff>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_AttachmentHandoff &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.attachmentHandoff(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_AttachmentHandoffCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_AttachmentHandoffCopyWith(
          BridgeEvent_AttachmentHandoff value,
          $Res Function(BridgeEvent_AttachmentHandoff) _then) =
      _$BridgeEvent_AttachmentHandoffCopyWithImpl;
  @useResult
  $Res call({AttachmentHandoffDto field0});
}

/// @nodoc
class _$BridgeEvent_AttachmentHandoffCopyWithImpl<$Res>
    implements $BridgeEvent_AttachmentHandoffCopyWith<$Res> {
  _$BridgeEvent_AttachmentHandoffCopyWithImpl(this._self, this._then);

  final BridgeEvent_AttachmentHandoff _self;
  final $Res Function(BridgeEvent_AttachmentHandoff) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_AttachmentHandoff(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as AttachmentHandoffDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_MeshStatusChanged extends BridgeEvent {
  const BridgeEvent_MeshStatusChanged(this.field0) : super._();

  final MeshStatusDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_MeshStatusChangedCopyWith<BridgeEvent_MeshStatusChanged>
      get copyWith => _$BridgeEvent_MeshStatusChangedCopyWithImpl<
          BridgeEvent_MeshStatusChanged>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_MeshStatusChanged &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.meshStatusChanged(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_MeshStatusChangedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_MeshStatusChangedCopyWith(
          BridgeEvent_MeshStatusChanged value,
          $Res Function(BridgeEvent_MeshStatusChanged) _then) =
      _$BridgeEvent_MeshStatusChangedCopyWithImpl;
  @useResult
  $Res call({MeshStatusDto field0});
}

/// @nodoc
class _$BridgeEvent_MeshStatusChangedCopyWithImpl<$Res>
    implements $BridgeEvent_MeshStatusChangedCopyWith<$Res> {
  _$BridgeEvent_MeshStatusChangedCopyWithImpl(this._self, this._then);

  final BridgeEvent_MeshStatusChanged _self;
  final $Res Function(BridgeEvent_MeshStatusChanged) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_MeshStatusChanged(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as MeshStatusDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_LedgerUpdated extends BridgeEvent {
  const BridgeEvent_LedgerUpdated({required this.ledger}) : super._();

  final String ledger;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_LedgerUpdatedCopyWith<BridgeEvent_LedgerUpdated> get copyWith =>
      _$BridgeEvent_LedgerUpdatedCopyWithImpl<BridgeEvent_LedgerUpdated>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_LedgerUpdated &&
            (identical(other.ledger, ledger) || other.ledger == ledger));
  }

  @override
  int get hashCode => Object.hash(runtimeType, ledger);

  @override
  String toString() {
    return 'BridgeEvent.ledgerUpdated(ledger: $ledger)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_LedgerUpdatedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_LedgerUpdatedCopyWith(BridgeEvent_LedgerUpdated value,
          $Res Function(BridgeEvent_LedgerUpdated) _then) =
      _$BridgeEvent_LedgerUpdatedCopyWithImpl;
  @useResult
  $Res call({String ledger});
}

/// @nodoc
class _$BridgeEvent_LedgerUpdatedCopyWithImpl<$Res>
    implements $BridgeEvent_LedgerUpdatedCopyWith<$Res> {
  _$BridgeEvent_LedgerUpdatedCopyWithImpl(this._self, this._then);

  final BridgeEvent_LedgerUpdated _self;
  final $Res Function(BridgeEvent_LedgerUpdated) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? ledger = null,
  }) {
    return _then(BridgeEvent_LedgerUpdated(
      ledger: null == ledger
          ? _self.ledger
          : ledger // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class BridgeEvent_TopicsChanged extends BridgeEvent {
  const BridgeEvent_TopicsChanged({required this.peer}) : super._();

  final String peer;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TopicsChangedCopyWith<BridgeEvent_TopicsChanged> get copyWith =>
      _$BridgeEvent_TopicsChangedCopyWithImpl<BridgeEvent_TopicsChanged>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TopicsChanged &&
            (identical(other.peer, peer) || other.peer == peer));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer);

  @override
  String toString() {
    return 'BridgeEvent.topicsChanged(peer: $peer)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TopicsChangedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TopicsChangedCopyWith(BridgeEvent_TopicsChanged value,
          $Res Function(BridgeEvent_TopicsChanged) _then) =
      _$BridgeEvent_TopicsChangedCopyWithImpl;
  @useResult
  $Res call({String peer});
}

/// @nodoc
class _$BridgeEvent_TopicsChangedCopyWithImpl<$Res>
    implements $BridgeEvent_TopicsChangedCopyWith<$Res> {
  _$BridgeEvent_TopicsChangedCopyWithImpl(this._self, this._then);

  final BridgeEvent_TopicsChanged _self;
  final $Res Function(BridgeEvent_TopicsChanged) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
  }) {
    return _then(BridgeEvent_TopicsChanged(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc
mixin _$CallSignal {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is CallSignal);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'CallSignal()';
  }
}

/// @nodoc
class $CallSignalCopyWith<$Res> {
  $CallSignalCopyWith(CallSignal _, $Res Function(CallSignal) __);
}

/// Adds pattern-matching-related methods to [CallSignal].
extension CallSignalPatterns on CallSignal {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(CallSignal_Offer value)? offer,
    TResult Function(CallSignal_Answer value)? answer,
    TResult Function(CallSignal_Ice value)? ice,
    TResult Function(CallSignal_Ringing value)? ringing,
    TResult Function(CallSignal_Busy value)? busy,
    TResult Function(CallSignal_Hangup value)? hangup,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer() when offer != null:
        return offer(_that);
      case CallSignal_Answer() when answer != null:
        return answer(_that);
      case CallSignal_Ice() when ice != null:
        return ice(_that);
      case CallSignal_Ringing() when ringing != null:
        return ringing(_that);
      case CallSignal_Busy() when busy != null:
        return busy(_that);
      case CallSignal_Hangup() when hangup != null:
        return hangup(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(CallSignal_Offer value) offer,
    required TResult Function(CallSignal_Answer value) answer,
    required TResult Function(CallSignal_Ice value) ice,
    required TResult Function(CallSignal_Ringing value) ringing,
    required TResult Function(CallSignal_Busy value) busy,
    required TResult Function(CallSignal_Hangup value) hangup,
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer():
        return offer(_that);
      case CallSignal_Answer():
        return answer(_that);
      case CallSignal_Ice():
        return ice(_that);
      case CallSignal_Ringing():
        return ringing(_that);
      case CallSignal_Busy():
        return busy(_that);
      case CallSignal_Hangup():
        return hangup(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(CallSignal_Offer value)? offer,
    TResult? Function(CallSignal_Answer value)? answer,
    TResult? Function(CallSignal_Ice value)? ice,
    TResult? Function(CallSignal_Ringing value)? ringing,
    TResult? Function(CallSignal_Busy value)? busy,
    TResult? Function(CallSignal_Hangup value)? hangup,
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer() when offer != null:
        return offer(_that);
      case CallSignal_Answer() when answer != null:
        return answer(_that);
      case CallSignal_Ice() when ice != null:
        return ice(_that);
      case CallSignal_Ringing() when ringing != null:
        return ringing(_that);
      case CallSignal_Busy() when busy != null:
        return busy(_that);
      case CallSignal_Hangup() when hangup != null:
        return hangup(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String sdp)? offer,
    TResult Function(String sdp)? answer,
    TResult Function(String candidate, String? sdpMid, int? sdpMLineIndex)? ice,
    TResult Function()? ringing,
    TResult Function()? busy,
    TResult Function(HangupReason reason)? hangup,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer() when offer != null:
        return offer(_that.sdp);
      case CallSignal_Answer() when answer != null:
        return answer(_that.sdp);
      case CallSignal_Ice() when ice != null:
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
      case CallSignal_Ringing() when ringing != null:
        return ringing();
      case CallSignal_Busy() when busy != null:
        return busy();
      case CallSignal_Hangup() when hangup != null:
        return hangup(_that.reason);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String sdp) offer,
    required TResult Function(String sdp) answer,
    required TResult Function(
            String candidate, String? sdpMid, int? sdpMLineIndex)
        ice,
    required TResult Function() ringing,
    required TResult Function() busy,
    required TResult Function(HangupReason reason) hangup,
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer():
        return offer(_that.sdp);
      case CallSignal_Answer():
        return answer(_that.sdp);
      case CallSignal_Ice():
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
      case CallSignal_Ringing():
        return ringing();
      case CallSignal_Busy():
        return busy();
      case CallSignal_Hangup():
        return hangup(_that.reason);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String sdp)? offer,
    TResult? Function(String sdp)? answer,
    TResult? Function(String candidate, String? sdpMid, int? sdpMLineIndex)?
        ice,
    TResult? Function()? ringing,
    TResult? Function()? busy,
    TResult? Function(HangupReason reason)? hangup,
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer() when offer != null:
        return offer(_that.sdp);
      case CallSignal_Answer() when answer != null:
        return answer(_that.sdp);
      case CallSignal_Ice() when ice != null:
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
      case CallSignal_Ringing() when ringing != null:
        return ringing();
      case CallSignal_Busy() when busy != null:
        return busy();
      case CallSignal_Hangup() when hangup != null:
        return hangup(_that.reason);
      case _:
        return null;
    }
  }
}

/// @nodoc

class CallSignal_Offer extends CallSignal {
  const CallSignal_Offer({required this.sdp}) : super._();

  final String sdp;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $CallSignal_OfferCopyWith<CallSignal_Offer> get copyWith =>
      _$CallSignal_OfferCopyWithImpl<CallSignal_Offer>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is CallSignal_Offer &&
            (identical(other.sdp, sdp) || other.sdp == sdp));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sdp);

  @override
  String toString() {
    return 'CallSignal.offer(sdp: $sdp)';
  }
}

/// @nodoc
abstract mixin class $CallSignal_OfferCopyWith<$Res>
    implements $CallSignalCopyWith<$Res> {
  factory $CallSignal_OfferCopyWith(
          CallSignal_Offer value, $Res Function(CallSignal_Offer) _then) =
      _$CallSignal_OfferCopyWithImpl;
  @useResult
  $Res call({String sdp});
}

/// @nodoc
class _$CallSignal_OfferCopyWithImpl<$Res>
    implements $CallSignal_OfferCopyWith<$Res> {
  _$CallSignal_OfferCopyWithImpl(this._self, this._then);

  final CallSignal_Offer _self;
  final $Res Function(CallSignal_Offer) _then;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sdp = null,
  }) {
    return _then(CallSignal_Offer(
      sdp: null == sdp
          ? _self.sdp
          : sdp // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class CallSignal_Answer extends CallSignal {
  const CallSignal_Answer({required this.sdp}) : super._();

  final String sdp;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $CallSignal_AnswerCopyWith<CallSignal_Answer> get copyWith =>
      _$CallSignal_AnswerCopyWithImpl<CallSignal_Answer>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is CallSignal_Answer &&
            (identical(other.sdp, sdp) || other.sdp == sdp));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sdp);

  @override
  String toString() {
    return 'CallSignal.answer(sdp: $sdp)';
  }
}

/// @nodoc
abstract mixin class $CallSignal_AnswerCopyWith<$Res>
    implements $CallSignalCopyWith<$Res> {
  factory $CallSignal_AnswerCopyWith(
          CallSignal_Answer value, $Res Function(CallSignal_Answer) _then) =
      _$CallSignal_AnswerCopyWithImpl;
  @useResult
  $Res call({String sdp});
}

/// @nodoc
class _$CallSignal_AnswerCopyWithImpl<$Res>
    implements $CallSignal_AnswerCopyWith<$Res> {
  _$CallSignal_AnswerCopyWithImpl(this._self, this._then);

  final CallSignal_Answer _self;
  final $Res Function(CallSignal_Answer) _then;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sdp = null,
  }) {
    return _then(CallSignal_Answer(
      sdp: null == sdp
          ? _self.sdp
          : sdp // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class CallSignal_Ice extends CallSignal {
  const CallSignal_Ice(
      {required this.candidate, this.sdpMid, this.sdpMLineIndex})
      : super._();

  final String candidate;
  final String? sdpMid;
  final int? sdpMLineIndex;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $CallSignal_IceCopyWith<CallSignal_Ice> get copyWith =>
      _$CallSignal_IceCopyWithImpl<CallSignal_Ice>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is CallSignal_Ice &&
            (identical(other.candidate, candidate) ||
                other.candidate == candidate) &&
            (identical(other.sdpMid, sdpMid) || other.sdpMid == sdpMid) &&
            (identical(other.sdpMLineIndex, sdpMLineIndex) ||
                other.sdpMLineIndex == sdpMLineIndex));
  }

  @override
  int get hashCode =>
      Object.hash(runtimeType, candidate, sdpMid, sdpMLineIndex);

  @override
  String toString() {
    return 'CallSignal.ice(candidate: $candidate, sdpMid: $sdpMid, sdpMLineIndex: $sdpMLineIndex)';
  }
}

/// @nodoc
abstract mixin class $CallSignal_IceCopyWith<$Res>
    implements $CallSignalCopyWith<$Res> {
  factory $CallSignal_IceCopyWith(
          CallSignal_Ice value, $Res Function(CallSignal_Ice) _then) =
      _$CallSignal_IceCopyWithImpl;
  @useResult
  $Res call({String candidate, String? sdpMid, int? sdpMLineIndex});
}

/// @nodoc
class _$CallSignal_IceCopyWithImpl<$Res>
    implements $CallSignal_IceCopyWith<$Res> {
  _$CallSignal_IceCopyWithImpl(this._self, this._then);

  final CallSignal_Ice _self;
  final $Res Function(CallSignal_Ice) _then;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? candidate = null,
    Object? sdpMid = freezed,
    Object? sdpMLineIndex = freezed,
  }) {
    return _then(CallSignal_Ice(
      candidate: null == candidate
          ? _self.candidate
          : candidate // ignore: cast_nullable_to_non_nullable
              as String,
      sdpMid: freezed == sdpMid
          ? _self.sdpMid
          : sdpMid // ignore: cast_nullable_to_non_nullable
              as String?,
      sdpMLineIndex: freezed == sdpMLineIndex
          ? _self.sdpMLineIndex
          : sdpMLineIndex // ignore: cast_nullable_to_non_nullable
              as int?,
    ));
  }
}

/// @nodoc

class CallSignal_Ringing extends CallSignal {
  const CallSignal_Ringing() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is CallSignal_Ringing);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'CallSignal.ringing()';
  }
}

/// @nodoc

class CallSignal_Busy extends CallSignal {
  const CallSignal_Busy() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is CallSignal_Busy);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'CallSignal.busy()';
  }
}

/// @nodoc

class CallSignal_Hangup extends CallSignal {
  const CallSignal_Hangup({required this.reason}) : super._();

  final HangupReason reason;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $CallSignal_HangupCopyWith<CallSignal_Hangup> get copyWith =>
      _$CallSignal_HangupCopyWithImpl<CallSignal_Hangup>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is CallSignal_Hangup &&
            (identical(other.reason, reason) || other.reason == reason));
  }

  @override
  int get hashCode => Object.hash(runtimeType, reason);

  @override
  String toString() {
    return 'CallSignal.hangup(reason: $reason)';
  }
}

/// @nodoc
abstract mixin class $CallSignal_HangupCopyWith<$Res>
    implements $CallSignalCopyWith<$Res> {
  factory $CallSignal_HangupCopyWith(
          CallSignal_Hangup value, $Res Function(CallSignal_Hangup) _then) =
      _$CallSignal_HangupCopyWithImpl;
  @useResult
  $Res call({HangupReason reason});
}

/// @nodoc
class _$CallSignal_HangupCopyWithImpl<$Res>
    implements $CallSignal_HangupCopyWith<$Res> {
  _$CallSignal_HangupCopyWithImpl(this._self, this._then);

  final CallSignal_Hangup _self;
  final $Res Function(CallSignal_Hangup) _then;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? reason = null,
  }) {
    return _then(CallSignal_Hangup(
      reason: null == reason
          ? _self.reason
          : reason // ignore: cast_nullable_to_non_nullable
              as HangupReason,
    ));
  }
}

/// @nodoc
mixin _$HandoffSignal {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is HandoffSignal);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'HandoffSignal()';
  }
}

/// @nodoc
class $HandoffSignalCopyWith<$Res> {
  $HandoffSignalCopyWith(HandoffSignal _, $Res Function(HandoffSignal) __);
}

/// Adds pattern-matching-related methods to [HandoffSignal].
extension HandoffSignalPatterns on HandoffSignal {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(HandoffSignal_Offer value)? offer,
    TResult Function(HandoffSignal_Accept value)? accept,
    TResult Function(HandoffSignal_Decline value)? decline,
    TResult Function(HandoffSignal_Refuse value)? refuse,
    TResult Function(HandoffSignal_Withdraw value)? withdraw,
    TResult Function(HandoffSignal_Transport value)? transport,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case HandoffSignal_Offer() when offer != null:
        return offer(_that);
      case HandoffSignal_Accept() when accept != null:
        return accept(_that);
      case HandoffSignal_Decline() when decline != null:
        return decline(_that);
      case HandoffSignal_Refuse() when refuse != null:
        return refuse(_that);
      case HandoffSignal_Withdraw() when withdraw != null:
        return withdraw(_that);
      case HandoffSignal_Transport() when transport != null:
        return transport(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(HandoffSignal_Offer value) offer,
    required TResult Function(HandoffSignal_Accept value) accept,
    required TResult Function(HandoffSignal_Decline value) decline,
    required TResult Function(HandoffSignal_Refuse value) refuse,
    required TResult Function(HandoffSignal_Withdraw value) withdraw,
    required TResult Function(HandoffSignal_Transport value) transport,
  }) {
    final _that = this;
    switch (_that) {
      case HandoffSignal_Offer():
        return offer(_that);
      case HandoffSignal_Accept():
        return accept(_that);
      case HandoffSignal_Decline():
        return decline(_that);
      case HandoffSignal_Refuse():
        return refuse(_that);
      case HandoffSignal_Withdraw():
        return withdraw(_that);
      case HandoffSignal_Transport():
        return transport(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(HandoffSignal_Offer value)? offer,
    TResult? Function(HandoffSignal_Accept value)? accept,
    TResult? Function(HandoffSignal_Decline value)? decline,
    TResult? Function(HandoffSignal_Refuse value)? refuse,
    TResult? Function(HandoffSignal_Withdraw value)? withdraw,
    TResult? Function(HandoffSignal_Transport value)? transport,
  }) {
    final _that = this;
    switch (_that) {
      case HandoffSignal_Offer() when offer != null:
        return offer(_that);
      case HandoffSignal_Accept() when accept != null:
        return accept(_that);
      case HandoffSignal_Decline() when decline != null:
        return decline(_that);
      case HandoffSignal_Refuse() when refuse != null:
        return refuse(_that);
      case HandoffSignal_Withdraw() when withdraw != null:
        return withdraw(_that);
      case HandoffSignal_Transport() when transport != null:
        return transport(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(AttachmentHandoff attachment)? offer,
    TResult Function()? accept,
    TResult Function()? decline,
    TResult Function(RefusalReason reason)? refuse,
    TResult Function()? withdraw,
    TResult Function(TransferSignal signal)? transport,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case HandoffSignal_Offer() when offer != null:
        return offer(_that.attachment);
      case HandoffSignal_Accept() when accept != null:
        return accept();
      case HandoffSignal_Decline() when decline != null:
        return decline();
      case HandoffSignal_Refuse() when refuse != null:
        return refuse(_that.reason);
      case HandoffSignal_Withdraw() when withdraw != null:
        return withdraw();
      case HandoffSignal_Transport() when transport != null:
        return transport(_that.signal);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(AttachmentHandoff attachment) offer,
    required TResult Function() accept,
    required TResult Function() decline,
    required TResult Function(RefusalReason reason) refuse,
    required TResult Function() withdraw,
    required TResult Function(TransferSignal signal) transport,
  }) {
    final _that = this;
    switch (_that) {
      case HandoffSignal_Offer():
        return offer(_that.attachment);
      case HandoffSignal_Accept():
        return accept();
      case HandoffSignal_Decline():
        return decline();
      case HandoffSignal_Refuse():
        return refuse(_that.reason);
      case HandoffSignal_Withdraw():
        return withdraw();
      case HandoffSignal_Transport():
        return transport(_that.signal);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(AttachmentHandoff attachment)? offer,
    TResult? Function()? accept,
    TResult? Function()? decline,
    TResult? Function(RefusalReason reason)? refuse,
    TResult? Function()? withdraw,
    TResult? Function(TransferSignal signal)? transport,
  }) {
    final _that = this;
    switch (_that) {
      case HandoffSignal_Offer() when offer != null:
        return offer(_that.attachment);
      case HandoffSignal_Accept() when accept != null:
        return accept();
      case HandoffSignal_Decline() when decline != null:
        return decline();
      case HandoffSignal_Refuse() when refuse != null:
        return refuse(_that.reason);
      case HandoffSignal_Withdraw() when withdraw != null:
        return withdraw();
      case HandoffSignal_Transport() when transport != null:
        return transport(_that.signal);
      case _:
        return null;
    }
  }
}

/// @nodoc

class HandoffSignal_Offer extends HandoffSignal {
  const HandoffSignal_Offer({required this.attachment}) : super._();

  final AttachmentHandoff attachment;

  /// Create a copy of HandoffSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $HandoffSignal_OfferCopyWith<HandoffSignal_Offer> get copyWith =>
      _$HandoffSignal_OfferCopyWithImpl<HandoffSignal_Offer>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is HandoffSignal_Offer &&
            (identical(other.attachment, attachment) ||
                other.attachment == attachment));
  }

  @override
  int get hashCode => Object.hash(runtimeType, attachment);

  @override
  String toString() {
    return 'HandoffSignal.offer(attachment: $attachment)';
  }
}

/// @nodoc
abstract mixin class $HandoffSignal_OfferCopyWith<$Res>
    implements $HandoffSignalCopyWith<$Res> {
  factory $HandoffSignal_OfferCopyWith(
          HandoffSignal_Offer value, $Res Function(HandoffSignal_Offer) _then) =
      _$HandoffSignal_OfferCopyWithImpl;
  @useResult
  $Res call({AttachmentHandoff attachment});
}

/// @nodoc
class _$HandoffSignal_OfferCopyWithImpl<$Res>
    implements $HandoffSignal_OfferCopyWith<$Res> {
  _$HandoffSignal_OfferCopyWithImpl(this._self, this._then);

  final HandoffSignal_Offer _self;
  final $Res Function(HandoffSignal_Offer) _then;

  /// Create a copy of HandoffSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? attachment = null,
  }) {
    return _then(HandoffSignal_Offer(
      attachment: null == attachment
          ? _self.attachment
          : attachment // ignore: cast_nullable_to_non_nullable
              as AttachmentHandoff,
    ));
  }
}

/// @nodoc

class HandoffSignal_Accept extends HandoffSignal {
  const HandoffSignal_Accept() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is HandoffSignal_Accept);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'HandoffSignal.accept()';
  }
}

/// @nodoc

class HandoffSignal_Decline extends HandoffSignal {
  const HandoffSignal_Decline() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is HandoffSignal_Decline);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'HandoffSignal.decline()';
  }
}

/// @nodoc

class HandoffSignal_Refuse extends HandoffSignal {
  const HandoffSignal_Refuse({required this.reason}) : super._();

  final RefusalReason reason;

  /// Create a copy of HandoffSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $HandoffSignal_RefuseCopyWith<HandoffSignal_Refuse> get copyWith =>
      _$HandoffSignal_RefuseCopyWithImpl<HandoffSignal_Refuse>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is HandoffSignal_Refuse &&
            (identical(other.reason, reason) || other.reason == reason));
  }

  @override
  int get hashCode => Object.hash(runtimeType, reason);

  @override
  String toString() {
    return 'HandoffSignal.refuse(reason: $reason)';
  }
}

/// @nodoc
abstract mixin class $HandoffSignal_RefuseCopyWith<$Res>
    implements $HandoffSignalCopyWith<$Res> {
  factory $HandoffSignal_RefuseCopyWith(HandoffSignal_Refuse value,
          $Res Function(HandoffSignal_Refuse) _then) =
      _$HandoffSignal_RefuseCopyWithImpl;
  @useResult
  $Res call({RefusalReason reason});

  $RefusalReasonCopyWith<$Res> get reason;
}

/// @nodoc
class _$HandoffSignal_RefuseCopyWithImpl<$Res>
    implements $HandoffSignal_RefuseCopyWith<$Res> {
  _$HandoffSignal_RefuseCopyWithImpl(this._self, this._then);

  final HandoffSignal_Refuse _self;
  final $Res Function(HandoffSignal_Refuse) _then;

  /// Create a copy of HandoffSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? reason = null,
  }) {
    return _then(HandoffSignal_Refuse(
      reason: null == reason
          ? _self.reason
          : reason // ignore: cast_nullable_to_non_nullable
              as RefusalReason,
    ));
  }

  /// Create a copy of HandoffSignal
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $RefusalReasonCopyWith<$Res> get reason {
    return $RefusalReasonCopyWith<$Res>(_self.reason, (value) {
      return _then(_self.copyWith(reason: value));
    });
  }
}

/// @nodoc

class HandoffSignal_Withdraw extends HandoffSignal {
  const HandoffSignal_Withdraw() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is HandoffSignal_Withdraw);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'HandoffSignal.withdraw()';
  }
}

/// @nodoc

class HandoffSignal_Transport extends HandoffSignal {
  const HandoffSignal_Transport({required this.signal}) : super._();

  final TransferSignal signal;

  /// Create a copy of HandoffSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $HandoffSignal_TransportCopyWith<HandoffSignal_Transport> get copyWith =>
      _$HandoffSignal_TransportCopyWithImpl<HandoffSignal_Transport>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is HandoffSignal_Transport &&
            (identical(other.signal, signal) || other.signal == signal));
  }

  @override
  int get hashCode => Object.hash(runtimeType, signal);

  @override
  String toString() {
    return 'HandoffSignal.transport(signal: $signal)';
  }
}

/// @nodoc
abstract mixin class $HandoffSignal_TransportCopyWith<$Res>
    implements $HandoffSignalCopyWith<$Res> {
  factory $HandoffSignal_TransportCopyWith(HandoffSignal_Transport value,
          $Res Function(HandoffSignal_Transport) _then) =
      _$HandoffSignal_TransportCopyWithImpl;
  @useResult
  $Res call({TransferSignal signal});

  $TransferSignalCopyWith<$Res> get signal;
}

/// @nodoc
class _$HandoffSignal_TransportCopyWithImpl<$Res>
    implements $HandoffSignal_TransportCopyWith<$Res> {
  _$HandoffSignal_TransportCopyWithImpl(this._self, this._then);

  final HandoffSignal_Transport _self;
  final $Res Function(HandoffSignal_Transport) _then;

  /// Create a copy of HandoffSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? signal = null,
  }) {
    return _then(HandoffSignal_Transport(
      signal: null == signal
          ? _self.signal
          : signal // ignore: cast_nullable_to_non_nullable
              as TransferSignal,
    ));
  }

  /// Create a copy of HandoffSignal
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $TransferSignalCopyWith<$Res> get signal {
    return $TransferSignalCopyWith<$Res>(_self.signal, (value) {
      return _then(_self.copyWith(signal: value));
    });
  }
}

/// @nodoc
mixin _$MusicLink {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is MusicLink);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'MusicLink()';
  }
}

/// @nodoc
class $MusicLinkCopyWith<$Res> {
  $MusicLinkCopyWith(MusicLink _, $Res Function(MusicLink) __);
}

/// Adds pattern-matching-related methods to [MusicLink].
extension MusicLinkPatterns on MusicLink {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(MusicLink_Spotify value)? spotify,
    TResult Function(MusicLink_AppleMusic value)? appleMusic,
    TResult Function(MusicLink_Youtube value)? youtube,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case MusicLink_Spotify() when spotify != null:
        return spotify(_that);
      case MusicLink_AppleMusic() when appleMusic != null:
        return appleMusic(_that);
      case MusicLink_Youtube() when youtube != null:
        return youtube(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(MusicLink_Spotify value) spotify,
    required TResult Function(MusicLink_AppleMusic value) appleMusic,
    required TResult Function(MusicLink_Youtube value) youtube,
  }) {
    final _that = this;
    switch (_that) {
      case MusicLink_Spotify():
        return spotify(_that);
      case MusicLink_AppleMusic():
        return appleMusic(_that);
      case MusicLink_Youtube():
        return youtube(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(MusicLink_Spotify value)? spotify,
    TResult? Function(MusicLink_AppleMusic value)? appleMusic,
    TResult? Function(MusicLink_Youtube value)? youtube,
  }) {
    final _that = this;
    switch (_that) {
      case MusicLink_Spotify() when spotify != null:
        return spotify(_that);
      case MusicLink_AppleMusic() when appleMusic != null:
        return appleMusic(_that);
      case MusicLink_Youtube() when youtube != null:
        return youtube(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String trackId)? spotify,
    TResult Function(String storefront, String trackId)? appleMusic,
    TResult Function(String videoId)? youtube,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case MusicLink_Spotify() when spotify != null:
        return spotify(_that.trackId);
      case MusicLink_AppleMusic() when appleMusic != null:
        return appleMusic(_that.storefront, _that.trackId);
      case MusicLink_Youtube() when youtube != null:
        return youtube(_that.videoId);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String trackId) spotify,
    required TResult Function(String storefront, String trackId) appleMusic,
    required TResult Function(String videoId) youtube,
  }) {
    final _that = this;
    switch (_that) {
      case MusicLink_Spotify():
        return spotify(_that.trackId);
      case MusicLink_AppleMusic():
        return appleMusic(_that.storefront, _that.trackId);
      case MusicLink_Youtube():
        return youtube(_that.videoId);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String trackId)? spotify,
    TResult? Function(String storefront, String trackId)? appleMusic,
    TResult? Function(String videoId)? youtube,
  }) {
    final _that = this;
    switch (_that) {
      case MusicLink_Spotify() when spotify != null:
        return spotify(_that.trackId);
      case MusicLink_AppleMusic() when appleMusic != null:
        return appleMusic(_that.storefront, _that.trackId);
      case MusicLink_Youtube() when youtube != null:
        return youtube(_that.videoId);
      case _:
        return null;
    }
  }
}

/// @nodoc

class MusicLink_Spotify extends MusicLink {
  const MusicLink_Spotify({required this.trackId}) : super._();

  final String trackId;

  /// Create a copy of MusicLink
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $MusicLink_SpotifyCopyWith<MusicLink_Spotify> get copyWith =>
      _$MusicLink_SpotifyCopyWithImpl<MusicLink_Spotify>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is MusicLink_Spotify &&
            (identical(other.trackId, trackId) || other.trackId == trackId));
  }

  @override
  int get hashCode => Object.hash(runtimeType, trackId);

  @override
  String toString() {
    return 'MusicLink.spotify(trackId: $trackId)';
  }
}

/// @nodoc
abstract mixin class $MusicLink_SpotifyCopyWith<$Res>
    implements $MusicLinkCopyWith<$Res> {
  factory $MusicLink_SpotifyCopyWith(
          MusicLink_Spotify value, $Res Function(MusicLink_Spotify) _then) =
      _$MusicLink_SpotifyCopyWithImpl;
  @useResult
  $Res call({String trackId});
}

/// @nodoc
class _$MusicLink_SpotifyCopyWithImpl<$Res>
    implements $MusicLink_SpotifyCopyWith<$Res> {
  _$MusicLink_SpotifyCopyWithImpl(this._self, this._then);

  final MusicLink_Spotify _self;
  final $Res Function(MusicLink_Spotify) _then;

  /// Create a copy of MusicLink
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? trackId = null,
  }) {
    return _then(MusicLink_Spotify(
      trackId: null == trackId
          ? _self.trackId
          : trackId // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class MusicLink_AppleMusic extends MusicLink {
  const MusicLink_AppleMusic({required this.storefront, required this.trackId})
      : super._();

  final String storefront;
  final String trackId;

  /// Create a copy of MusicLink
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $MusicLink_AppleMusicCopyWith<MusicLink_AppleMusic> get copyWith =>
      _$MusicLink_AppleMusicCopyWithImpl<MusicLink_AppleMusic>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is MusicLink_AppleMusic &&
            (identical(other.storefront, storefront) ||
                other.storefront == storefront) &&
            (identical(other.trackId, trackId) || other.trackId == trackId));
  }

  @override
  int get hashCode => Object.hash(runtimeType, storefront, trackId);

  @override
  String toString() {
    return 'MusicLink.appleMusic(storefront: $storefront, trackId: $trackId)';
  }
}

/// @nodoc
abstract mixin class $MusicLink_AppleMusicCopyWith<$Res>
    implements $MusicLinkCopyWith<$Res> {
  factory $MusicLink_AppleMusicCopyWith(MusicLink_AppleMusic value,
          $Res Function(MusicLink_AppleMusic) _then) =
      _$MusicLink_AppleMusicCopyWithImpl;
  @useResult
  $Res call({String storefront, String trackId});
}

/// @nodoc
class _$MusicLink_AppleMusicCopyWithImpl<$Res>
    implements $MusicLink_AppleMusicCopyWith<$Res> {
  _$MusicLink_AppleMusicCopyWithImpl(this._self, this._then);

  final MusicLink_AppleMusic _self;
  final $Res Function(MusicLink_AppleMusic) _then;

  /// Create a copy of MusicLink
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? storefront = null,
    Object? trackId = null,
  }) {
    return _then(MusicLink_AppleMusic(
      storefront: null == storefront
          ? _self.storefront
          : storefront // ignore: cast_nullable_to_non_nullable
              as String,
      trackId: null == trackId
          ? _self.trackId
          : trackId // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class MusicLink_Youtube extends MusicLink {
  const MusicLink_Youtube({required this.videoId}) : super._();

  final String videoId;

  /// Create a copy of MusicLink
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $MusicLink_YoutubeCopyWith<MusicLink_Youtube> get copyWith =>
      _$MusicLink_YoutubeCopyWithImpl<MusicLink_Youtube>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is MusicLink_Youtube &&
            (identical(other.videoId, videoId) || other.videoId == videoId));
  }

  @override
  int get hashCode => Object.hash(runtimeType, videoId);

  @override
  String toString() {
    return 'MusicLink.youtube(videoId: $videoId)';
  }
}

/// @nodoc
abstract mixin class $MusicLink_YoutubeCopyWith<$Res>
    implements $MusicLinkCopyWith<$Res> {
  factory $MusicLink_YoutubeCopyWith(
          MusicLink_Youtube value, $Res Function(MusicLink_Youtube) _then) =
      _$MusicLink_YoutubeCopyWithImpl;
  @useResult
  $Res call({String videoId});
}

/// @nodoc
class _$MusicLink_YoutubeCopyWithImpl<$Res>
    implements $MusicLink_YoutubeCopyWith<$Res> {
  _$MusicLink_YoutubeCopyWithImpl(this._self, this._then);

  final MusicLink_Youtube _self;
  final $Res Function(MusicLink_Youtube) _then;

  /// Create a copy of MusicLink
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? videoId = null,
  }) {
    return _then(MusicLink_Youtube(
      videoId: null == videoId
          ? _self.videoId
          : videoId // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc
mixin _$RefusalReason {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is RefusalReason);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'RefusalReason()';
  }
}

/// @nodoc
class $RefusalReasonCopyWith<$Res> {
  $RefusalReasonCopyWith(RefusalReason _, $Res Function(RefusalReason) __);
}

/// Adds pattern-matching-related methods to [RefusalReason].
extension RefusalReasonPatterns on RefusalReason {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(RefusalReason_RelayForbidden value)? relayForbidden,
    TResult Function(RefusalReason_TooLargeForRelay value)? tooLargeForRelay,
    TResult Function(RefusalReason_PathUnknown value)? pathUnknown,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case RefusalReason_RelayForbidden() when relayForbidden != null:
        return relayForbidden(_that);
      case RefusalReason_TooLargeForRelay() when tooLargeForRelay != null:
        return tooLargeForRelay(_that);
      case RefusalReason_PathUnknown() when pathUnknown != null:
        return pathUnknown(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(RefusalReason_RelayForbidden value)
        relayForbidden,
    required TResult Function(RefusalReason_TooLargeForRelay value)
        tooLargeForRelay,
    required TResult Function(RefusalReason_PathUnknown value) pathUnknown,
  }) {
    final _that = this;
    switch (_that) {
      case RefusalReason_RelayForbidden():
        return relayForbidden(_that);
      case RefusalReason_TooLargeForRelay():
        return tooLargeForRelay(_that);
      case RefusalReason_PathUnknown():
        return pathUnknown(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(RefusalReason_RelayForbidden value)? relayForbidden,
    TResult? Function(RefusalReason_TooLargeForRelay value)? tooLargeForRelay,
    TResult? Function(RefusalReason_PathUnknown value)? pathUnknown,
  }) {
    final _that = this;
    switch (_that) {
      case RefusalReason_RelayForbidden() when relayForbidden != null:
        return relayForbidden(_that);
      case RefusalReason_TooLargeForRelay() when tooLargeForRelay != null:
        return tooLargeForRelay(_that);
      case RefusalReason_PathUnknown() when pathUnknown != null:
        return pathUnknown(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function()? relayForbidden,
    TResult Function(BigInt limit)? tooLargeForRelay,
    TResult Function()? pathUnknown,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case RefusalReason_RelayForbidden() when relayForbidden != null:
        return relayForbidden();
      case RefusalReason_TooLargeForRelay() when tooLargeForRelay != null:
        return tooLargeForRelay(_that.limit);
      case RefusalReason_PathUnknown() when pathUnknown != null:
        return pathUnknown();
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function() relayForbidden,
    required TResult Function(BigInt limit) tooLargeForRelay,
    required TResult Function() pathUnknown,
  }) {
    final _that = this;
    switch (_that) {
      case RefusalReason_RelayForbidden():
        return relayForbidden();
      case RefusalReason_TooLargeForRelay():
        return tooLargeForRelay(_that.limit);
      case RefusalReason_PathUnknown():
        return pathUnknown();
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function()? relayForbidden,
    TResult? Function(BigInt limit)? tooLargeForRelay,
    TResult? Function()? pathUnknown,
  }) {
    final _that = this;
    switch (_that) {
      case RefusalReason_RelayForbidden() when relayForbidden != null:
        return relayForbidden();
      case RefusalReason_TooLargeForRelay() when tooLargeForRelay != null:
        return tooLargeForRelay(_that.limit);
      case RefusalReason_PathUnknown() when pathUnknown != null:
        return pathUnknown();
      case _:
        return null;
    }
  }
}

/// @nodoc

class RefusalReason_RelayForbidden extends RefusalReason {
  const RefusalReason_RelayForbidden() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is RefusalReason_RelayForbidden);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'RefusalReason.relayForbidden()';
  }
}

/// @nodoc

class RefusalReason_TooLargeForRelay extends RefusalReason {
  const RefusalReason_TooLargeForRelay({required this.limit}) : super._();

  final BigInt limit;

  /// Create a copy of RefusalReason
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $RefusalReason_TooLargeForRelayCopyWith<RefusalReason_TooLargeForRelay>
      get copyWith => _$RefusalReason_TooLargeForRelayCopyWithImpl<
          RefusalReason_TooLargeForRelay>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is RefusalReason_TooLargeForRelay &&
            (identical(other.limit, limit) || other.limit == limit));
  }

  @override
  int get hashCode => Object.hash(runtimeType, limit);

  @override
  String toString() {
    return 'RefusalReason.tooLargeForRelay(limit: $limit)';
  }
}

/// @nodoc
abstract mixin class $RefusalReason_TooLargeForRelayCopyWith<$Res>
    implements $RefusalReasonCopyWith<$Res> {
  factory $RefusalReason_TooLargeForRelayCopyWith(
          RefusalReason_TooLargeForRelay value,
          $Res Function(RefusalReason_TooLargeForRelay) _then) =
      _$RefusalReason_TooLargeForRelayCopyWithImpl;
  @useResult
  $Res call({BigInt limit});
}

/// @nodoc
class _$RefusalReason_TooLargeForRelayCopyWithImpl<$Res>
    implements $RefusalReason_TooLargeForRelayCopyWith<$Res> {
  _$RefusalReason_TooLargeForRelayCopyWithImpl(this._self, this._then);

  final RefusalReason_TooLargeForRelay _self;
  final $Res Function(RefusalReason_TooLargeForRelay) _then;

  /// Create a copy of RefusalReason
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? limit = null,
  }) {
    return _then(RefusalReason_TooLargeForRelay(
      limit: null == limit
          ? _self.limit
          : limit // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc

class RefusalReason_PathUnknown extends RefusalReason {
  const RefusalReason_PathUnknown() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is RefusalReason_PathUnknown);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'RefusalReason.pathUnknown()';
  }
}

/// @nodoc
mixin _$RelayPolicy {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is RelayPolicy);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'RelayPolicy()';
  }
}

/// @nodoc
class $RelayPolicyCopyWith<$Res> {
  $RelayPolicyCopyWith(RelayPolicy _, $Res Function(RelayPolicy) __);
}

/// Adds pattern-matching-related methods to [RelayPolicy].
extension RelayPolicyPatterns on RelayPolicy {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(RelayPolicy_DirectOnly value)? directOnly,
    TResult Function(RelayPolicy_UnderBytes value)? underBytes,
    TResult Function(RelayPolicy_AskEachTime value)? askEachTime,
    TResult Function(RelayPolicy_Always value)? always,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case RelayPolicy_DirectOnly() when directOnly != null:
        return directOnly(_that);
      case RelayPolicy_UnderBytes() when underBytes != null:
        return underBytes(_that);
      case RelayPolicy_AskEachTime() when askEachTime != null:
        return askEachTime(_that);
      case RelayPolicy_Always() when always != null:
        return always(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(RelayPolicy_DirectOnly value) directOnly,
    required TResult Function(RelayPolicy_UnderBytes value) underBytes,
    required TResult Function(RelayPolicy_AskEachTime value) askEachTime,
    required TResult Function(RelayPolicy_Always value) always,
  }) {
    final _that = this;
    switch (_that) {
      case RelayPolicy_DirectOnly():
        return directOnly(_that);
      case RelayPolicy_UnderBytes():
        return underBytes(_that);
      case RelayPolicy_AskEachTime():
        return askEachTime(_that);
      case RelayPolicy_Always():
        return always(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(RelayPolicy_DirectOnly value)? directOnly,
    TResult? Function(RelayPolicy_UnderBytes value)? underBytes,
    TResult? Function(RelayPolicy_AskEachTime value)? askEachTime,
    TResult? Function(RelayPolicy_Always value)? always,
  }) {
    final _that = this;
    switch (_that) {
      case RelayPolicy_DirectOnly() when directOnly != null:
        return directOnly(_that);
      case RelayPolicy_UnderBytes() when underBytes != null:
        return underBytes(_that);
      case RelayPolicy_AskEachTime() when askEachTime != null:
        return askEachTime(_that);
      case RelayPolicy_Always() when always != null:
        return always(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function()? directOnly,
    TResult Function(BigInt limit)? underBytes,
    TResult Function()? askEachTime,
    TResult Function()? always,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case RelayPolicy_DirectOnly() when directOnly != null:
        return directOnly();
      case RelayPolicy_UnderBytes() when underBytes != null:
        return underBytes(_that.limit);
      case RelayPolicy_AskEachTime() when askEachTime != null:
        return askEachTime();
      case RelayPolicy_Always() when always != null:
        return always();
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function() directOnly,
    required TResult Function(BigInt limit) underBytes,
    required TResult Function() askEachTime,
    required TResult Function() always,
  }) {
    final _that = this;
    switch (_that) {
      case RelayPolicy_DirectOnly():
        return directOnly();
      case RelayPolicy_UnderBytes():
        return underBytes(_that.limit);
      case RelayPolicy_AskEachTime():
        return askEachTime();
      case RelayPolicy_Always():
        return always();
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function()? directOnly,
    TResult? Function(BigInt limit)? underBytes,
    TResult? Function()? askEachTime,
    TResult? Function()? always,
  }) {
    final _that = this;
    switch (_that) {
      case RelayPolicy_DirectOnly() when directOnly != null:
        return directOnly();
      case RelayPolicy_UnderBytes() when underBytes != null:
        return underBytes(_that.limit);
      case RelayPolicy_AskEachTime() when askEachTime != null:
        return askEachTime();
      case RelayPolicy_Always() when always != null:
        return always();
      case _:
        return null;
    }
  }
}

/// @nodoc

class RelayPolicy_DirectOnly extends RelayPolicy {
  const RelayPolicy_DirectOnly() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is RelayPolicy_DirectOnly);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'RelayPolicy.directOnly()';
  }
}

/// @nodoc

class RelayPolicy_UnderBytes extends RelayPolicy {
  const RelayPolicy_UnderBytes({required this.limit}) : super._();

  final BigInt limit;

  /// Create a copy of RelayPolicy
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $RelayPolicy_UnderBytesCopyWith<RelayPolicy_UnderBytes> get copyWith =>
      _$RelayPolicy_UnderBytesCopyWithImpl<RelayPolicy_UnderBytes>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is RelayPolicy_UnderBytes &&
            (identical(other.limit, limit) || other.limit == limit));
  }

  @override
  int get hashCode => Object.hash(runtimeType, limit);

  @override
  String toString() {
    return 'RelayPolicy.underBytes(limit: $limit)';
  }
}

/// @nodoc
abstract mixin class $RelayPolicy_UnderBytesCopyWith<$Res>
    implements $RelayPolicyCopyWith<$Res> {
  factory $RelayPolicy_UnderBytesCopyWith(RelayPolicy_UnderBytes value,
          $Res Function(RelayPolicy_UnderBytes) _then) =
      _$RelayPolicy_UnderBytesCopyWithImpl;
  @useResult
  $Res call({BigInt limit});
}

/// @nodoc
class _$RelayPolicy_UnderBytesCopyWithImpl<$Res>
    implements $RelayPolicy_UnderBytesCopyWith<$Res> {
  _$RelayPolicy_UnderBytesCopyWithImpl(this._self, this._then);

  final RelayPolicy_UnderBytes _self;
  final $Res Function(RelayPolicy_UnderBytes) _then;

  /// Create a copy of RelayPolicy
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? limit = null,
  }) {
    return _then(RelayPolicy_UnderBytes(
      limit: null == limit
          ? _self.limit
          : limit // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc

class RelayPolicy_AskEachTime extends RelayPolicy {
  const RelayPolicy_AskEachTime() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is RelayPolicy_AskEachTime);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'RelayPolicy.askEachTime()';
  }
}

/// @nodoc

class RelayPolicy_Always extends RelayPolicy {
  const RelayPolicy_Always() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is RelayPolicy_Always);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'RelayPolicy.always()';
  }
}

/// @nodoc
mixin _$ShareSignal {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is ShareSignal);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'ShareSignal()';
  }
}

/// @nodoc
class $ShareSignalCopyWith<$Res> {
  $ShareSignalCopyWith(ShareSignal _, $Res Function(ShareSignal) __);
}

/// Adds pattern-matching-related methods to [ShareSignal].
extension ShareSignalPatterns on ShareSignal {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(ShareSignal_Ask value)? ask,
    TResult Function(ShareSignal_Offer value)? offer,
    TResult Function(ShareSignal_Accept value)? accept,
    TResult Function(ShareSignal_Refuse value)? refuse,
    TResult Function(ShareSignal_Transport value)? transport,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case ShareSignal_Ask() when ask != null:
        return ask(_that);
      case ShareSignal_Offer() when offer != null:
        return offer(_that);
      case ShareSignal_Accept() when accept != null:
        return accept(_that);
      case ShareSignal_Refuse() when refuse != null:
        return refuse(_that);
      case ShareSignal_Transport() when transport != null:
        return transport(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(ShareSignal_Ask value) ask,
    required TResult Function(ShareSignal_Offer value) offer,
    required TResult Function(ShareSignal_Accept value) accept,
    required TResult Function(ShareSignal_Refuse value) refuse,
    required TResult Function(ShareSignal_Transport value) transport,
  }) {
    final _that = this;
    switch (_that) {
      case ShareSignal_Ask():
        return ask(_that);
      case ShareSignal_Offer():
        return offer(_that);
      case ShareSignal_Accept():
        return accept(_that);
      case ShareSignal_Refuse():
        return refuse(_that);
      case ShareSignal_Transport():
        return transport(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(ShareSignal_Ask value)? ask,
    TResult? Function(ShareSignal_Offer value)? offer,
    TResult? Function(ShareSignal_Accept value)? accept,
    TResult? Function(ShareSignal_Refuse value)? refuse,
    TResult? Function(ShareSignal_Transport value)? transport,
  }) {
    final _that = this;
    switch (_that) {
      case ShareSignal_Ask() when ask != null:
        return ask(_that);
      case ShareSignal_Offer() when offer != null:
        return offer(_that);
      case ShareSignal_Accept() when accept != null:
        return accept(_that);
      case ShareSignal_Refuse() when refuse != null:
        return refuse(_that);
      case ShareSignal_Transport() when transport != null:
        return transport(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function()? ask,
    TResult Function(ShareOffer offer)? offer,
    TResult Function()? accept,
    TResult Function(RefusalReason reason)? refuse,
    TResult Function(TransferSignal signal)? transport,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case ShareSignal_Ask() when ask != null:
        return ask();
      case ShareSignal_Offer() when offer != null:
        return offer(_that.offer);
      case ShareSignal_Accept() when accept != null:
        return accept();
      case ShareSignal_Refuse() when refuse != null:
        return refuse(_that.reason);
      case ShareSignal_Transport() when transport != null:
        return transport(_that.signal);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function() ask,
    required TResult Function(ShareOffer offer) offer,
    required TResult Function() accept,
    required TResult Function(RefusalReason reason) refuse,
    required TResult Function(TransferSignal signal) transport,
  }) {
    final _that = this;
    switch (_that) {
      case ShareSignal_Ask():
        return ask();
      case ShareSignal_Offer():
        return offer(_that.offer);
      case ShareSignal_Accept():
        return accept();
      case ShareSignal_Refuse():
        return refuse(_that.reason);
      case ShareSignal_Transport():
        return transport(_that.signal);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function()? ask,
    TResult? Function(ShareOffer offer)? offer,
    TResult? Function()? accept,
    TResult? Function(RefusalReason reason)? refuse,
    TResult? Function(TransferSignal signal)? transport,
  }) {
    final _that = this;
    switch (_that) {
      case ShareSignal_Ask() when ask != null:
        return ask();
      case ShareSignal_Offer() when offer != null:
        return offer(_that.offer);
      case ShareSignal_Accept() when accept != null:
        return accept();
      case ShareSignal_Refuse() when refuse != null:
        return refuse(_that.reason);
      case ShareSignal_Transport() when transport != null:
        return transport(_that.signal);
      case _:
        return null;
    }
  }
}

/// @nodoc

class ShareSignal_Ask extends ShareSignal {
  const ShareSignal_Ask() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is ShareSignal_Ask);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'ShareSignal.ask()';
  }
}

/// @nodoc

class ShareSignal_Offer extends ShareSignal {
  const ShareSignal_Offer({required this.offer}) : super._();

  final ShareOffer offer;

  /// Create a copy of ShareSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $ShareSignal_OfferCopyWith<ShareSignal_Offer> get copyWith =>
      _$ShareSignal_OfferCopyWithImpl<ShareSignal_Offer>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is ShareSignal_Offer &&
            (identical(other.offer, offer) || other.offer == offer));
  }

  @override
  int get hashCode => Object.hash(runtimeType, offer);

  @override
  String toString() {
    return 'ShareSignal.offer(offer: $offer)';
  }
}

/// @nodoc
abstract mixin class $ShareSignal_OfferCopyWith<$Res>
    implements $ShareSignalCopyWith<$Res> {
  factory $ShareSignal_OfferCopyWith(
          ShareSignal_Offer value, $Res Function(ShareSignal_Offer) _then) =
      _$ShareSignal_OfferCopyWithImpl;
  @useResult
  $Res call({ShareOffer offer});
}

/// @nodoc
class _$ShareSignal_OfferCopyWithImpl<$Res>
    implements $ShareSignal_OfferCopyWith<$Res> {
  _$ShareSignal_OfferCopyWithImpl(this._self, this._then);

  final ShareSignal_Offer _self;
  final $Res Function(ShareSignal_Offer) _then;

  /// Create a copy of ShareSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? offer = null,
  }) {
    return _then(ShareSignal_Offer(
      offer: null == offer
          ? _self.offer
          : offer // ignore: cast_nullable_to_non_nullable
              as ShareOffer,
    ));
  }
}

/// @nodoc

class ShareSignal_Accept extends ShareSignal {
  const ShareSignal_Accept() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is ShareSignal_Accept);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'ShareSignal.accept()';
  }
}

/// @nodoc

class ShareSignal_Refuse extends ShareSignal {
  const ShareSignal_Refuse({required this.reason}) : super._();

  final RefusalReason reason;

  /// Create a copy of ShareSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $ShareSignal_RefuseCopyWith<ShareSignal_Refuse> get copyWith =>
      _$ShareSignal_RefuseCopyWithImpl<ShareSignal_Refuse>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is ShareSignal_Refuse &&
            (identical(other.reason, reason) || other.reason == reason));
  }

  @override
  int get hashCode => Object.hash(runtimeType, reason);

  @override
  String toString() {
    return 'ShareSignal.refuse(reason: $reason)';
  }
}

/// @nodoc
abstract mixin class $ShareSignal_RefuseCopyWith<$Res>
    implements $ShareSignalCopyWith<$Res> {
  factory $ShareSignal_RefuseCopyWith(
          ShareSignal_Refuse value, $Res Function(ShareSignal_Refuse) _then) =
      _$ShareSignal_RefuseCopyWithImpl;
  @useResult
  $Res call({RefusalReason reason});

  $RefusalReasonCopyWith<$Res> get reason;
}

/// @nodoc
class _$ShareSignal_RefuseCopyWithImpl<$Res>
    implements $ShareSignal_RefuseCopyWith<$Res> {
  _$ShareSignal_RefuseCopyWithImpl(this._self, this._then);

  final ShareSignal_Refuse _self;
  final $Res Function(ShareSignal_Refuse) _then;

  /// Create a copy of ShareSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? reason = null,
  }) {
    return _then(ShareSignal_Refuse(
      reason: null == reason
          ? _self.reason
          : reason // ignore: cast_nullable_to_non_nullable
              as RefusalReason,
    ));
  }

  /// Create a copy of ShareSignal
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $RefusalReasonCopyWith<$Res> get reason {
    return $RefusalReasonCopyWith<$Res>(_self.reason, (value) {
      return _then(_self.copyWith(reason: value));
    });
  }
}

/// @nodoc

class ShareSignal_Transport extends ShareSignal {
  const ShareSignal_Transport({required this.signal}) : super._();

  final TransferSignal signal;

  /// Create a copy of ShareSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $ShareSignal_TransportCopyWith<ShareSignal_Transport> get copyWith =>
      _$ShareSignal_TransportCopyWithImpl<ShareSignal_Transport>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is ShareSignal_Transport &&
            (identical(other.signal, signal) || other.signal == signal));
  }

  @override
  int get hashCode => Object.hash(runtimeType, signal);

  @override
  String toString() {
    return 'ShareSignal.transport(signal: $signal)';
  }
}

/// @nodoc
abstract mixin class $ShareSignal_TransportCopyWith<$Res>
    implements $ShareSignalCopyWith<$Res> {
  factory $ShareSignal_TransportCopyWith(ShareSignal_Transport value,
          $Res Function(ShareSignal_Transport) _then) =
      _$ShareSignal_TransportCopyWithImpl;
  @useResult
  $Res call({TransferSignal signal});

  $TransferSignalCopyWith<$Res> get signal;
}

/// @nodoc
class _$ShareSignal_TransportCopyWithImpl<$Res>
    implements $ShareSignal_TransportCopyWith<$Res> {
  _$ShareSignal_TransportCopyWithImpl(this._self, this._then);

  final ShareSignal_Transport _self;
  final $Res Function(ShareSignal_Transport) _then;

  /// Create a copy of ShareSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? signal = null,
  }) {
    return _then(ShareSignal_Transport(
      signal: null == signal
          ? _self.signal
          : signal // ignore: cast_nullable_to_non_nullable
              as TransferSignal,
    ));
  }

  /// Create a copy of ShareSignal
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $TransferSignalCopyWith<$Res> get signal {
    return $TransferSignalCopyWith<$Res>(_self.signal, (value) {
      return _then(_self.copyWith(signal: value));
    });
  }
}

/// @nodoc
mixin _$SyncVerdict {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is SyncVerdict);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'SyncVerdict()';
  }
}

/// @nodoc
class $SyncVerdictCopyWith<$Res> {
  $SyncVerdictCopyWith(SyncVerdict _, $Res Function(SyncVerdict) __);
}

/// Adds pattern-matching-related methods to [SyncVerdict].
extension SyncVerdictPatterns on SyncVerdict {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(SyncVerdict_Hold value)? hold,
    TResult Function(SyncVerdict_Adopt value)? adopt,
    TResult Function(SyncVerdict_Nudge value)? nudge,
    TResult Function(SyncVerdict_Seek value)? seek,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold() when hold != null:
        return hold(_that);
      case SyncVerdict_Adopt() when adopt != null:
        return adopt(_that);
      case SyncVerdict_Nudge() when nudge != null:
        return nudge(_that);
      case SyncVerdict_Seek() when seek != null:
        return seek(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(SyncVerdict_Hold value) hold,
    required TResult Function(SyncVerdict_Adopt value) adopt,
    required TResult Function(SyncVerdict_Nudge value) nudge,
    required TResult Function(SyncVerdict_Seek value) seek,
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold():
        return hold(_that);
      case SyncVerdict_Adopt():
        return adopt(_that);
      case SyncVerdict_Nudge():
        return nudge(_that);
      case SyncVerdict_Seek():
        return seek(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(SyncVerdict_Hold value)? hold,
    TResult? Function(SyncVerdict_Adopt value)? adopt,
    TResult? Function(SyncVerdict_Nudge value)? nudge,
    TResult? Function(SyncVerdict_Seek value)? seek,
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold() when hold != null:
        return hold(_that);
      case SyncVerdict_Adopt() when adopt != null:
        return adopt(_that);
      case SyncVerdict_Nudge() when nudge != null:
        return nudge(_that);
      case SyncVerdict_Seek() when seek != null:
        return seek(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function()? hold,
    TResult Function(BigInt posMs, bool playing, BigInt seq)? adopt,
    TResult Function(double rate)? nudge,
    TResult Function(BigInt posMs)? seek,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold() when hold != null:
        return hold();
      case SyncVerdict_Adopt() when adopt != null:
        return adopt(_that.posMs, _that.playing, _that.seq);
      case SyncVerdict_Nudge() when nudge != null:
        return nudge(_that.rate);
      case SyncVerdict_Seek() when seek != null:
        return seek(_that.posMs);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function() hold,
    required TResult Function(BigInt posMs, bool playing, BigInt seq) adopt,
    required TResult Function(double rate) nudge,
    required TResult Function(BigInt posMs) seek,
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold():
        return hold();
      case SyncVerdict_Adopt():
        return adopt(_that.posMs, _that.playing, _that.seq);
      case SyncVerdict_Nudge():
        return nudge(_that.rate);
      case SyncVerdict_Seek():
        return seek(_that.posMs);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function()? hold,
    TResult? Function(BigInt posMs, bool playing, BigInt seq)? adopt,
    TResult? Function(double rate)? nudge,
    TResult? Function(BigInt posMs)? seek,
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold() when hold != null:
        return hold();
      case SyncVerdict_Adopt() when adopt != null:
        return adopt(_that.posMs, _that.playing, _that.seq);
      case SyncVerdict_Nudge() when nudge != null:
        return nudge(_that.rate);
      case SyncVerdict_Seek() when seek != null:
        return seek(_that.posMs);
      case _:
        return null;
    }
  }
}

/// @nodoc

class SyncVerdict_Hold extends SyncVerdict {
  const SyncVerdict_Hold() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is SyncVerdict_Hold);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'SyncVerdict.hold()';
  }
}

/// @nodoc

class SyncVerdict_Adopt extends SyncVerdict {
  const SyncVerdict_Adopt(
      {required this.posMs, required this.playing, required this.seq})
      : super._();

  final BigInt posMs;
  final bool playing;
  final BigInt seq;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $SyncVerdict_AdoptCopyWith<SyncVerdict_Adopt> get copyWith =>
      _$SyncVerdict_AdoptCopyWithImpl<SyncVerdict_Adopt>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is SyncVerdict_Adopt &&
            (identical(other.posMs, posMs) || other.posMs == posMs) &&
            (identical(other.playing, playing) || other.playing == playing) &&
            (identical(other.seq, seq) || other.seq == seq));
  }

  @override
  int get hashCode => Object.hash(runtimeType, posMs, playing, seq);

  @override
  String toString() {
    return 'SyncVerdict.adopt(posMs: $posMs, playing: $playing, seq: $seq)';
  }
}

/// @nodoc
abstract mixin class $SyncVerdict_AdoptCopyWith<$Res>
    implements $SyncVerdictCopyWith<$Res> {
  factory $SyncVerdict_AdoptCopyWith(
          SyncVerdict_Adopt value, $Res Function(SyncVerdict_Adopt) _then) =
      _$SyncVerdict_AdoptCopyWithImpl;
  @useResult
  $Res call({BigInt posMs, bool playing, BigInt seq});
}

/// @nodoc
class _$SyncVerdict_AdoptCopyWithImpl<$Res>
    implements $SyncVerdict_AdoptCopyWith<$Res> {
  _$SyncVerdict_AdoptCopyWithImpl(this._self, this._then);

  final SyncVerdict_Adopt _self;
  final $Res Function(SyncVerdict_Adopt) _then;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? posMs = null,
    Object? playing = null,
    Object? seq = null,
  }) {
    return _then(SyncVerdict_Adopt(
      posMs: null == posMs
          ? _self.posMs
          : posMs // ignore: cast_nullable_to_non_nullable
              as BigInt,
      playing: null == playing
          ? _self.playing
          : playing // ignore: cast_nullable_to_non_nullable
              as bool,
      seq: null == seq
          ? _self.seq
          : seq // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc

class SyncVerdict_Nudge extends SyncVerdict {
  const SyncVerdict_Nudge({required this.rate}) : super._();

  final double rate;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $SyncVerdict_NudgeCopyWith<SyncVerdict_Nudge> get copyWith =>
      _$SyncVerdict_NudgeCopyWithImpl<SyncVerdict_Nudge>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is SyncVerdict_Nudge &&
            (identical(other.rate, rate) || other.rate == rate));
  }

  @override
  int get hashCode => Object.hash(runtimeType, rate);

  @override
  String toString() {
    return 'SyncVerdict.nudge(rate: $rate)';
  }
}

/// @nodoc
abstract mixin class $SyncVerdict_NudgeCopyWith<$Res>
    implements $SyncVerdictCopyWith<$Res> {
  factory $SyncVerdict_NudgeCopyWith(
          SyncVerdict_Nudge value, $Res Function(SyncVerdict_Nudge) _then) =
      _$SyncVerdict_NudgeCopyWithImpl;
  @useResult
  $Res call({double rate});
}

/// @nodoc
class _$SyncVerdict_NudgeCopyWithImpl<$Res>
    implements $SyncVerdict_NudgeCopyWith<$Res> {
  _$SyncVerdict_NudgeCopyWithImpl(this._self, this._then);

  final SyncVerdict_Nudge _self;
  final $Res Function(SyncVerdict_Nudge) _then;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? rate = null,
  }) {
    return _then(SyncVerdict_Nudge(
      rate: null == rate
          ? _self.rate
          : rate // ignore: cast_nullable_to_non_nullable
              as double,
    ));
  }
}

/// @nodoc

class SyncVerdict_Seek extends SyncVerdict {
  const SyncVerdict_Seek({required this.posMs}) : super._();

  final BigInt posMs;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $SyncVerdict_SeekCopyWith<SyncVerdict_Seek> get copyWith =>
      _$SyncVerdict_SeekCopyWithImpl<SyncVerdict_Seek>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is SyncVerdict_Seek &&
            (identical(other.posMs, posMs) || other.posMs == posMs));
  }

  @override
  int get hashCode => Object.hash(runtimeType, posMs);

  @override
  String toString() {
    return 'SyncVerdict.seek(posMs: $posMs)';
  }
}

/// @nodoc
abstract mixin class $SyncVerdict_SeekCopyWith<$Res>
    implements $SyncVerdictCopyWith<$Res> {
  factory $SyncVerdict_SeekCopyWith(
          SyncVerdict_Seek value, $Res Function(SyncVerdict_Seek) _then) =
      _$SyncVerdict_SeekCopyWithImpl;
  @useResult
  $Res call({BigInt posMs});
}

/// @nodoc
class _$SyncVerdict_SeekCopyWithImpl<$Res>
    implements $SyncVerdict_SeekCopyWith<$Res> {
  _$SyncVerdict_SeekCopyWithImpl(this._self, this._then);

  final SyncVerdict_Seek _self;
  final $Res Function(SyncVerdict_Seek) _then;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? posMs = null,
  }) {
    return _then(SyncVerdict_Seek(
      posMs: null == posMs
          ? _self.posMs
          : posMs // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc
mixin _$TogetherContent {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is TogetherContent);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'TogetherContent()';
  }
}

/// @nodoc
class $TogetherContentCopyWith<$Res> {
  $TogetherContentCopyWith(
      TogetherContent _, $Res Function(TogetherContent) __);
}

/// Adds pattern-matching-related methods to [TogetherContent].
extension TogetherContentPatterns on TogetherContent {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TogetherContent_LocalFile value)? localFile,
    TResult Function(TogetherContent_Youtube value)? youtube,
    TResult Function(TogetherContent_Service value)? service,
    TResult Function(TogetherContent_Stream value)? stream,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile() when localFile != null:
        return localFile(_that);
      case TogetherContent_Youtube() when youtube != null:
        return youtube(_that);
      case TogetherContent_Service() when service != null:
        return service(_that);
      case TogetherContent_Stream() when stream != null:
        return stream(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TogetherContent_LocalFile value) localFile,
    required TResult Function(TogetherContent_Youtube value) youtube,
    required TResult Function(TogetherContent_Service value) service,
    required TResult Function(TogetherContent_Stream value) stream,
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile():
        return localFile(_that);
      case TogetherContent_Youtube():
        return youtube(_that);
      case TogetherContent_Service():
        return service(_that);
      case TogetherContent_Stream():
        return stream(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TogetherContent_LocalFile value)? localFile,
    TResult? Function(TogetherContent_Youtube value)? youtube,
    TResult? Function(TogetherContent_Service value)? service,
    TResult? Function(TogetherContent_Stream value)? stream,
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile() when localFile != null:
        return localFile(_that);
      case TogetherContent_Youtube() when youtube != null:
        return youtube(_that);
      case TogetherContent_Service() when service != null:
        return service(_that);
      case TogetherContent_Stream() when stream != null:
        return stream(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(BigInt durationMs, Recording? recording)? localFile,
    TResult Function(String videoId)? youtube,
    TResult Function(MusicLink link, Recording? recording)? service,
    TResult Function(String url, Recording? recording, BigInt? durationMs)?
        stream,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile() when localFile != null:
        return localFile(_that.durationMs, _that.recording);
      case TogetherContent_Youtube() when youtube != null:
        return youtube(_that.videoId);
      case TogetherContent_Service() when service != null:
        return service(_that.link, _that.recording);
      case TogetherContent_Stream() when stream != null:
        return stream(_that.url, _that.recording, _that.durationMs);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(BigInt durationMs, Recording? recording)
        localFile,
    required TResult Function(String videoId) youtube,
    required TResult Function(MusicLink link, Recording? recording) service,
    required TResult Function(
            String url, Recording? recording, BigInt? durationMs)
        stream,
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile():
        return localFile(_that.durationMs, _that.recording);
      case TogetherContent_Youtube():
        return youtube(_that.videoId);
      case TogetherContent_Service():
        return service(_that.link, _that.recording);
      case TogetherContent_Stream():
        return stream(_that.url, _that.recording, _that.durationMs);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(BigInt durationMs, Recording? recording)? localFile,
    TResult? Function(String videoId)? youtube,
    TResult? Function(MusicLink link, Recording? recording)? service,
    TResult? Function(String url, Recording? recording, BigInt? durationMs)?
        stream,
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile() when localFile != null:
        return localFile(_that.durationMs, _that.recording);
      case TogetherContent_Youtube() when youtube != null:
        return youtube(_that.videoId);
      case TogetherContent_Service() when service != null:
        return service(_that.link, _that.recording);
      case TogetherContent_Stream() when stream != null:
        return stream(_that.url, _that.recording, _that.durationMs);
      case _:
        return null;
    }
  }
}

/// @nodoc

class TogetherContent_LocalFile extends TogetherContent {
  const TogetherContent_LocalFile({required this.durationMs, this.recording})
      : super._();

  final BigInt durationMs;
  final Recording? recording;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TogetherContent_LocalFileCopyWith<TogetherContent_LocalFile> get copyWith =>
      _$TogetherContent_LocalFileCopyWithImpl<TogetherContent_LocalFile>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TogetherContent_LocalFile &&
            (identical(other.durationMs, durationMs) ||
                other.durationMs == durationMs) &&
            (identical(other.recording, recording) ||
                other.recording == recording));
  }

  @override
  int get hashCode => Object.hash(runtimeType, durationMs, recording);

  @override
  String toString() {
    return 'TogetherContent.localFile(durationMs: $durationMs, recording: $recording)';
  }
}

/// @nodoc
abstract mixin class $TogetherContent_LocalFileCopyWith<$Res>
    implements $TogetherContentCopyWith<$Res> {
  factory $TogetherContent_LocalFileCopyWith(TogetherContent_LocalFile value,
          $Res Function(TogetherContent_LocalFile) _then) =
      _$TogetherContent_LocalFileCopyWithImpl;
  @useResult
  $Res call({BigInt durationMs, Recording? recording});
}

/// @nodoc
class _$TogetherContent_LocalFileCopyWithImpl<$Res>
    implements $TogetherContent_LocalFileCopyWith<$Res> {
  _$TogetherContent_LocalFileCopyWithImpl(this._self, this._then);

  final TogetherContent_LocalFile _self;
  final $Res Function(TogetherContent_LocalFile) _then;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? durationMs = null,
    Object? recording = freezed,
  }) {
    return _then(TogetherContent_LocalFile(
      durationMs: null == durationMs
          ? _self.durationMs
          : durationMs // ignore: cast_nullable_to_non_nullable
              as BigInt,
      recording: freezed == recording
          ? _self.recording
          : recording // ignore: cast_nullable_to_non_nullable
              as Recording?,
    ));
  }
}

/// @nodoc

class TogetherContent_Youtube extends TogetherContent {
  const TogetherContent_Youtube({required this.videoId}) : super._();

  final String videoId;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TogetherContent_YoutubeCopyWith<TogetherContent_Youtube> get copyWith =>
      _$TogetherContent_YoutubeCopyWithImpl<TogetherContent_Youtube>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TogetherContent_Youtube &&
            (identical(other.videoId, videoId) || other.videoId == videoId));
  }

  @override
  int get hashCode => Object.hash(runtimeType, videoId);

  @override
  String toString() {
    return 'TogetherContent.youtube(videoId: $videoId)';
  }
}

/// @nodoc
abstract mixin class $TogetherContent_YoutubeCopyWith<$Res>
    implements $TogetherContentCopyWith<$Res> {
  factory $TogetherContent_YoutubeCopyWith(TogetherContent_Youtube value,
          $Res Function(TogetherContent_Youtube) _then) =
      _$TogetherContent_YoutubeCopyWithImpl;
  @useResult
  $Res call({String videoId});
}

/// @nodoc
class _$TogetherContent_YoutubeCopyWithImpl<$Res>
    implements $TogetherContent_YoutubeCopyWith<$Res> {
  _$TogetherContent_YoutubeCopyWithImpl(this._self, this._then);

  final TogetherContent_Youtube _self;
  final $Res Function(TogetherContent_Youtube) _then;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? videoId = null,
  }) {
    return _then(TogetherContent_Youtube(
      videoId: null == videoId
          ? _self.videoId
          : videoId // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class TogetherContent_Service extends TogetherContent {
  const TogetherContent_Service({required this.link, this.recording})
      : super._();

  final MusicLink link;
  final Recording? recording;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TogetherContent_ServiceCopyWith<TogetherContent_Service> get copyWith =>
      _$TogetherContent_ServiceCopyWithImpl<TogetherContent_Service>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TogetherContent_Service &&
            (identical(other.link, link) || other.link == link) &&
            (identical(other.recording, recording) ||
                other.recording == recording));
  }

  @override
  int get hashCode => Object.hash(runtimeType, link, recording);

  @override
  String toString() {
    return 'TogetherContent.service(link: $link, recording: $recording)';
  }
}

/// @nodoc
abstract mixin class $TogetherContent_ServiceCopyWith<$Res>
    implements $TogetherContentCopyWith<$Res> {
  factory $TogetherContent_ServiceCopyWith(TogetherContent_Service value,
          $Res Function(TogetherContent_Service) _then) =
      _$TogetherContent_ServiceCopyWithImpl;
  @useResult
  $Res call({MusicLink link, Recording? recording});

  $MusicLinkCopyWith<$Res> get link;
}

/// @nodoc
class _$TogetherContent_ServiceCopyWithImpl<$Res>
    implements $TogetherContent_ServiceCopyWith<$Res> {
  _$TogetherContent_ServiceCopyWithImpl(this._self, this._then);

  final TogetherContent_Service _self;
  final $Res Function(TogetherContent_Service) _then;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? link = null,
    Object? recording = freezed,
  }) {
    return _then(TogetherContent_Service(
      link: null == link
          ? _self.link
          : link // ignore: cast_nullable_to_non_nullable
              as MusicLink,
      recording: freezed == recording
          ? _self.recording
          : recording // ignore: cast_nullable_to_non_nullable
              as Recording?,
    ));
  }

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $MusicLinkCopyWith<$Res> get link {
    return $MusicLinkCopyWith<$Res>(_self.link, (value) {
      return _then(_self.copyWith(link: value));
    });
  }
}

/// @nodoc

class TogetherContent_Stream extends TogetherContent {
  const TogetherContent_Stream(
      {required this.url, this.recording, this.durationMs})
      : super._();

  final String url;
  final Recording? recording;
  final BigInt? durationMs;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TogetherContent_StreamCopyWith<TogetherContent_Stream> get copyWith =>
      _$TogetherContent_StreamCopyWithImpl<TogetherContent_Stream>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TogetherContent_Stream &&
            (identical(other.url, url) || other.url == url) &&
            (identical(other.recording, recording) ||
                other.recording == recording) &&
            (identical(other.durationMs, durationMs) ||
                other.durationMs == durationMs));
  }

  @override
  int get hashCode => Object.hash(runtimeType, url, recording, durationMs);

  @override
  String toString() {
    return 'TogetherContent.stream(url: $url, recording: $recording, durationMs: $durationMs)';
  }
}

/// @nodoc
abstract mixin class $TogetherContent_StreamCopyWith<$Res>
    implements $TogetherContentCopyWith<$Res> {
  factory $TogetherContent_StreamCopyWith(TogetherContent_Stream value,
          $Res Function(TogetherContent_Stream) _then) =
      _$TogetherContent_StreamCopyWithImpl;
  @useResult
  $Res call({String url, Recording? recording, BigInt? durationMs});
}

/// @nodoc
class _$TogetherContent_StreamCopyWithImpl<$Res>
    implements $TogetherContent_StreamCopyWith<$Res> {
  _$TogetherContent_StreamCopyWithImpl(this._self, this._then);

  final TogetherContent_Stream _self;
  final $Res Function(TogetherContent_Stream) _then;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? url = null,
    Object? recording = freezed,
    Object? durationMs = freezed,
  }) {
    return _then(TogetherContent_Stream(
      url: null == url
          ? _self.url
          : url // ignore: cast_nullable_to_non_nullable
              as String,
      recording: freezed == recording
          ? _self.recording
          : recording // ignore: cast_nullable_to_non_nullable
              as Recording?,
      durationMs: freezed == durationMs
          ? _self.durationMs
          : durationMs // ignore: cast_nullable_to_non_nullable
              as BigInt?,
    ));
  }
}

/// @nodoc
mixin _$TransferSignal {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is TransferSignal);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'TransferSignal()';
  }
}

/// @nodoc
class $TransferSignalCopyWith<$Res> {
  $TransferSignalCopyWith(TransferSignal _, $Res Function(TransferSignal) __);
}

/// Adds pattern-matching-related methods to [TransferSignal].
extension TransferSignalPatterns on TransferSignal {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TransferSignal_Offer value)? offer,
    TResult Function(TransferSignal_Answer value)? answer,
    TResult Function(TransferSignal_Ice value)? ice,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case TransferSignal_Offer() when offer != null:
        return offer(_that);
      case TransferSignal_Answer() when answer != null:
        return answer(_that);
      case TransferSignal_Ice() when ice != null:
        return ice(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TransferSignal_Offer value) offer,
    required TResult Function(TransferSignal_Answer value) answer,
    required TResult Function(TransferSignal_Ice value) ice,
  }) {
    final _that = this;
    switch (_that) {
      case TransferSignal_Offer():
        return offer(_that);
      case TransferSignal_Answer():
        return answer(_that);
      case TransferSignal_Ice():
        return ice(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TransferSignal_Offer value)? offer,
    TResult? Function(TransferSignal_Answer value)? answer,
    TResult? Function(TransferSignal_Ice value)? ice,
  }) {
    final _that = this;
    switch (_that) {
      case TransferSignal_Offer() when offer != null:
        return offer(_that);
      case TransferSignal_Answer() when answer != null:
        return answer(_that);
      case TransferSignal_Ice() when ice != null:
        return ice(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String sdp)? offer,
    TResult Function(String sdp)? answer,
    TResult Function(String candidate, String? sdpMid, int? sdpMLineIndex)? ice,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case TransferSignal_Offer() when offer != null:
        return offer(_that.sdp);
      case TransferSignal_Answer() when answer != null:
        return answer(_that.sdp);
      case TransferSignal_Ice() when ice != null:
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String sdp) offer,
    required TResult Function(String sdp) answer,
    required TResult Function(
            String candidate, String? sdpMid, int? sdpMLineIndex)
        ice,
  }) {
    final _that = this;
    switch (_that) {
      case TransferSignal_Offer():
        return offer(_that.sdp);
      case TransferSignal_Answer():
        return answer(_that.sdp);
      case TransferSignal_Ice():
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String sdp)? offer,
    TResult? Function(String sdp)? answer,
    TResult? Function(String candidate, String? sdpMid, int? sdpMLineIndex)?
        ice,
  }) {
    final _that = this;
    switch (_that) {
      case TransferSignal_Offer() when offer != null:
        return offer(_that.sdp);
      case TransferSignal_Answer() when answer != null:
        return answer(_that.sdp);
      case TransferSignal_Ice() when ice != null:
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
      case _:
        return null;
    }
  }
}

/// @nodoc

class TransferSignal_Offer extends TransferSignal {
  const TransferSignal_Offer({required this.sdp}) : super._();

  final String sdp;

  /// Create a copy of TransferSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TransferSignal_OfferCopyWith<TransferSignal_Offer> get copyWith =>
      _$TransferSignal_OfferCopyWithImpl<TransferSignal_Offer>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TransferSignal_Offer &&
            (identical(other.sdp, sdp) || other.sdp == sdp));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sdp);

  @override
  String toString() {
    return 'TransferSignal.offer(sdp: $sdp)';
  }
}

/// @nodoc
abstract mixin class $TransferSignal_OfferCopyWith<$Res>
    implements $TransferSignalCopyWith<$Res> {
  factory $TransferSignal_OfferCopyWith(TransferSignal_Offer value,
          $Res Function(TransferSignal_Offer) _then) =
      _$TransferSignal_OfferCopyWithImpl;
  @useResult
  $Res call({String sdp});
}

/// @nodoc
class _$TransferSignal_OfferCopyWithImpl<$Res>
    implements $TransferSignal_OfferCopyWith<$Res> {
  _$TransferSignal_OfferCopyWithImpl(this._self, this._then);

  final TransferSignal_Offer _self;
  final $Res Function(TransferSignal_Offer) _then;

  /// Create a copy of TransferSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sdp = null,
  }) {
    return _then(TransferSignal_Offer(
      sdp: null == sdp
          ? _self.sdp
          : sdp // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class TransferSignal_Answer extends TransferSignal {
  const TransferSignal_Answer({required this.sdp}) : super._();

  final String sdp;

  /// Create a copy of TransferSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TransferSignal_AnswerCopyWith<TransferSignal_Answer> get copyWith =>
      _$TransferSignal_AnswerCopyWithImpl<TransferSignal_Answer>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TransferSignal_Answer &&
            (identical(other.sdp, sdp) || other.sdp == sdp));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sdp);

  @override
  String toString() {
    return 'TransferSignal.answer(sdp: $sdp)';
  }
}

/// @nodoc
abstract mixin class $TransferSignal_AnswerCopyWith<$Res>
    implements $TransferSignalCopyWith<$Res> {
  factory $TransferSignal_AnswerCopyWith(TransferSignal_Answer value,
          $Res Function(TransferSignal_Answer) _then) =
      _$TransferSignal_AnswerCopyWithImpl;
  @useResult
  $Res call({String sdp});
}

/// @nodoc
class _$TransferSignal_AnswerCopyWithImpl<$Res>
    implements $TransferSignal_AnswerCopyWith<$Res> {
  _$TransferSignal_AnswerCopyWithImpl(this._self, this._then);

  final TransferSignal_Answer _self;
  final $Res Function(TransferSignal_Answer) _then;

  /// Create a copy of TransferSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sdp = null,
  }) {
    return _then(TransferSignal_Answer(
      sdp: null == sdp
          ? _self.sdp
          : sdp // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class TransferSignal_Ice extends TransferSignal {
  const TransferSignal_Ice(
      {required this.candidate, this.sdpMid, this.sdpMLineIndex})
      : super._();

  final String candidate;
  final String? sdpMid;
  final int? sdpMLineIndex;

  /// Create a copy of TransferSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TransferSignal_IceCopyWith<TransferSignal_Ice> get copyWith =>
      _$TransferSignal_IceCopyWithImpl<TransferSignal_Ice>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TransferSignal_Ice &&
            (identical(other.candidate, candidate) ||
                other.candidate == candidate) &&
            (identical(other.sdpMid, sdpMid) || other.sdpMid == sdpMid) &&
            (identical(other.sdpMLineIndex, sdpMLineIndex) ||
                other.sdpMLineIndex == sdpMLineIndex));
  }

  @override
  int get hashCode =>
      Object.hash(runtimeType, candidate, sdpMid, sdpMLineIndex);

  @override
  String toString() {
    return 'TransferSignal.ice(candidate: $candidate, sdpMid: $sdpMid, sdpMLineIndex: $sdpMLineIndex)';
  }
}

/// @nodoc
abstract mixin class $TransferSignal_IceCopyWith<$Res>
    implements $TransferSignalCopyWith<$Res> {
  factory $TransferSignal_IceCopyWith(
          TransferSignal_Ice value, $Res Function(TransferSignal_Ice) _then) =
      _$TransferSignal_IceCopyWithImpl;
  @useResult
  $Res call({String candidate, String? sdpMid, int? sdpMLineIndex});
}

/// @nodoc
class _$TransferSignal_IceCopyWithImpl<$Res>
    implements $TransferSignal_IceCopyWith<$Res> {
  _$TransferSignal_IceCopyWithImpl(this._self, this._then);

  final TransferSignal_Ice _self;
  final $Res Function(TransferSignal_Ice) _then;

  /// Create a copy of TransferSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? candidate = null,
    Object? sdpMid = freezed,
    Object? sdpMLineIndex = freezed,
  }) {
    return _then(TransferSignal_Ice(
      candidate: null == candidate
          ? _self.candidate
          : candidate // ignore: cast_nullable_to_non_nullable
              as String,
      sdpMid: freezed == sdpMid
          ? _self.sdpMid
          : sdpMid // ignore: cast_nullable_to_non_nullable
              as String?,
      sdpMLineIndex: freezed == sdpMLineIndex
          ? _self.sdpMLineIndex
          : sdpMLineIndex // ignore: cast_nullable_to_non_nullable
              as int?,
    ));
  }
}

/// @nodoc
mixin _$TransferVerdict {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is TransferVerdict);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'TransferVerdict()';
  }
}

/// @nodoc
class $TransferVerdictCopyWith<$Res> {
  $TransferVerdictCopyWith(
      TransferVerdict _, $Res Function(TransferVerdict) __);
}

/// Adds pattern-matching-related methods to [TransferVerdict].
extension TransferVerdictPatterns on TransferVerdict {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TransferVerdict_Allow value)? allow,
    TResult Function(TransferVerdict_NeedsConsent value)? needsConsent,
    TResult Function(TransferVerdict_Refuse value)? refuse,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case TransferVerdict_Allow() when allow != null:
        return allow(_that);
      case TransferVerdict_NeedsConsent() when needsConsent != null:
        return needsConsent(_that);
      case TransferVerdict_Refuse() when refuse != null:
        return refuse(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TransferVerdict_Allow value) allow,
    required TResult Function(TransferVerdict_NeedsConsent value) needsConsent,
    required TResult Function(TransferVerdict_Refuse value) refuse,
  }) {
    final _that = this;
    switch (_that) {
      case TransferVerdict_Allow():
        return allow(_that);
      case TransferVerdict_NeedsConsent():
        return needsConsent(_that);
      case TransferVerdict_Refuse():
        return refuse(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TransferVerdict_Allow value)? allow,
    TResult? Function(TransferVerdict_NeedsConsent value)? needsConsent,
    TResult? Function(TransferVerdict_Refuse value)? refuse,
  }) {
    final _that = this;
    switch (_that) {
      case TransferVerdict_Allow() when allow != null:
        return allow(_that);
      case TransferVerdict_NeedsConsent() when needsConsent != null:
        return needsConsent(_that);
      case TransferVerdict_Refuse() when refuse != null:
        return refuse(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function()? allow,
    TResult Function(BigInt relayedBytes)? needsConsent,
    TResult Function(RefusalReason reason)? refuse,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case TransferVerdict_Allow() when allow != null:
        return allow();
      case TransferVerdict_NeedsConsent() when needsConsent != null:
        return needsConsent(_that.relayedBytes);
      case TransferVerdict_Refuse() when refuse != null:
        return refuse(_that.reason);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function() allow,
    required TResult Function(BigInt relayedBytes) needsConsent,
    required TResult Function(RefusalReason reason) refuse,
  }) {
    final _that = this;
    switch (_that) {
      case TransferVerdict_Allow():
        return allow();
      case TransferVerdict_NeedsConsent():
        return needsConsent(_that.relayedBytes);
      case TransferVerdict_Refuse():
        return refuse(_that.reason);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function()? allow,
    TResult? Function(BigInt relayedBytes)? needsConsent,
    TResult? Function(RefusalReason reason)? refuse,
  }) {
    final _that = this;
    switch (_that) {
      case TransferVerdict_Allow() when allow != null:
        return allow();
      case TransferVerdict_NeedsConsent() when needsConsent != null:
        return needsConsent(_that.relayedBytes);
      case TransferVerdict_Refuse() when refuse != null:
        return refuse(_that.reason);
      case _:
        return null;
    }
  }
}

/// @nodoc

class TransferVerdict_Allow extends TransferVerdict {
  const TransferVerdict_Allow() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is TransferVerdict_Allow);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'TransferVerdict.allow()';
  }
}

/// @nodoc

class TransferVerdict_NeedsConsent extends TransferVerdict {
  const TransferVerdict_NeedsConsent({required this.relayedBytes}) : super._();

  final BigInt relayedBytes;

  /// Create a copy of TransferVerdict
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TransferVerdict_NeedsConsentCopyWith<TransferVerdict_NeedsConsent>
      get copyWith => _$TransferVerdict_NeedsConsentCopyWithImpl<
          TransferVerdict_NeedsConsent>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TransferVerdict_NeedsConsent &&
            (identical(other.relayedBytes, relayedBytes) ||
                other.relayedBytes == relayedBytes));
  }

  @override
  int get hashCode => Object.hash(runtimeType, relayedBytes);

  @override
  String toString() {
    return 'TransferVerdict.needsConsent(relayedBytes: $relayedBytes)';
  }
}

/// @nodoc
abstract mixin class $TransferVerdict_NeedsConsentCopyWith<$Res>
    implements $TransferVerdictCopyWith<$Res> {
  factory $TransferVerdict_NeedsConsentCopyWith(
          TransferVerdict_NeedsConsent value,
          $Res Function(TransferVerdict_NeedsConsent) _then) =
      _$TransferVerdict_NeedsConsentCopyWithImpl;
  @useResult
  $Res call({BigInt relayedBytes});
}

/// @nodoc
class _$TransferVerdict_NeedsConsentCopyWithImpl<$Res>
    implements $TransferVerdict_NeedsConsentCopyWith<$Res> {
  _$TransferVerdict_NeedsConsentCopyWithImpl(this._self, this._then);

  final TransferVerdict_NeedsConsent _self;
  final $Res Function(TransferVerdict_NeedsConsent) _then;

  /// Create a copy of TransferVerdict
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? relayedBytes = null,
  }) {
    return _then(TransferVerdict_NeedsConsent(
      relayedBytes: null == relayedBytes
          ? _self.relayedBytes
          : relayedBytes // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc

class TransferVerdict_Refuse extends TransferVerdict {
  const TransferVerdict_Refuse({required this.reason}) : super._();

  final RefusalReason reason;

  /// Create a copy of TransferVerdict
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TransferVerdict_RefuseCopyWith<TransferVerdict_Refuse> get copyWith =>
      _$TransferVerdict_RefuseCopyWithImpl<TransferVerdict_Refuse>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TransferVerdict_Refuse &&
            (identical(other.reason, reason) || other.reason == reason));
  }

  @override
  int get hashCode => Object.hash(runtimeType, reason);

  @override
  String toString() {
    return 'TransferVerdict.refuse(reason: $reason)';
  }
}

/// @nodoc
abstract mixin class $TransferVerdict_RefuseCopyWith<$Res>
    implements $TransferVerdictCopyWith<$Res> {
  factory $TransferVerdict_RefuseCopyWith(TransferVerdict_Refuse value,
          $Res Function(TransferVerdict_Refuse) _then) =
      _$TransferVerdict_RefuseCopyWithImpl;
  @useResult
  $Res call({RefusalReason reason});

  $RefusalReasonCopyWith<$Res> get reason;
}

/// @nodoc
class _$TransferVerdict_RefuseCopyWithImpl<$Res>
    implements $TransferVerdict_RefuseCopyWith<$Res> {
  _$TransferVerdict_RefuseCopyWithImpl(this._self, this._then);

  final TransferVerdict_Refuse _self;
  final $Res Function(TransferVerdict_Refuse) _then;

  /// Create a copy of TransferVerdict
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? reason = null,
  }) {
    return _then(TransferVerdict_Refuse(
      reason: null == reason
          ? _self.reason
          : reason // ignore: cast_nullable_to_non_nullable
              as RefusalReason,
    ));
  }

  /// Create a copy of TransferVerdict
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $RefusalReasonCopyWith<$Res> get reason {
    return $RefusalReasonCopyWith<$Res>(_self.reason, (value) {
      return _then(_self.copyWith(reason: value));
    });
  }
}

/// @nodoc
mixin _$UiError {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is UiError);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError()';
  }
}

/// @nodoc
class $UiErrorCopyWith<$Res> {
  $UiErrorCopyWith(UiError _, $Res Function(UiError) __);
}

/// Adds pattern-matching-related methods to [UiError].
extension UiErrorPatterns on UiError {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(UiError_UnknownWorkspace value)? unknownWorkspace,
    TResult Function(UiError_Transition value)? transition,
    TResult Function(UiError_NoIdentity value)? noIdentity,
    TResult Function(UiError_StoreLocked value)? storeLocked,
    TResult Function(UiError_Crypto value)? crypto,
    TResult Function(UiError_Storage value)? storage,
    TResult Function(UiError_VaultLocked value)? vaultLocked,
    TResult Function(UiError_Engine value)? engine,
    TResult Function(UiError_Catalogue value)? catalogue,
    TResult Function(UiError_Download value)? download,
    TResult Function(UiError_CatalogueUnavailable value)? catalogueUnavailable,
    TResult Function(UiError_Travel value)? travel,
    TResult Function(UiError_TravelUnavailable value)? travelUnavailable,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace() when unknownWorkspace != null:
        return unknownWorkspace(_that);
      case UiError_Transition() when transition != null:
        return transition(_that);
      case UiError_NoIdentity() when noIdentity != null:
        return noIdentity(_that);
      case UiError_StoreLocked() when storeLocked != null:
        return storeLocked(_that);
      case UiError_Crypto() when crypto != null:
        return crypto(_that);
      case UiError_Storage() when storage != null:
        return storage(_that);
      case UiError_VaultLocked() when vaultLocked != null:
        return vaultLocked(_that);
      case UiError_Engine() when engine != null:
        return engine(_that);
      case UiError_Catalogue() when catalogue != null:
        return catalogue(_that);
      case UiError_Download() when download != null:
        return download(_that);
      case UiError_CatalogueUnavailable() when catalogueUnavailable != null:
        return catalogueUnavailable(_that);
      case UiError_Travel() when travel != null:
        return travel(_that);
      case UiError_TravelUnavailable() when travelUnavailable != null:
        return travelUnavailable(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(UiError_UnknownWorkspace value) unknownWorkspace,
    required TResult Function(UiError_Transition value) transition,
    required TResult Function(UiError_NoIdentity value) noIdentity,
    required TResult Function(UiError_StoreLocked value) storeLocked,
    required TResult Function(UiError_Crypto value) crypto,
    required TResult Function(UiError_Storage value) storage,
    required TResult Function(UiError_VaultLocked value) vaultLocked,
    required TResult Function(UiError_Engine value) engine,
    required TResult Function(UiError_Catalogue value) catalogue,
    required TResult Function(UiError_Download value) download,
    required TResult Function(UiError_CatalogueUnavailable value)
        catalogueUnavailable,
    required TResult Function(UiError_Travel value) travel,
    required TResult Function(UiError_TravelUnavailable value)
        travelUnavailable,
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace():
        return unknownWorkspace(_that);
      case UiError_Transition():
        return transition(_that);
      case UiError_NoIdentity():
        return noIdentity(_that);
      case UiError_StoreLocked():
        return storeLocked(_that);
      case UiError_Crypto():
        return crypto(_that);
      case UiError_Storage():
        return storage(_that);
      case UiError_VaultLocked():
        return vaultLocked(_that);
      case UiError_Engine():
        return engine(_that);
      case UiError_Catalogue():
        return catalogue(_that);
      case UiError_Download():
        return download(_that);
      case UiError_CatalogueUnavailable():
        return catalogueUnavailable(_that);
      case UiError_Travel():
        return travel(_that);
      case UiError_TravelUnavailable():
        return travelUnavailable(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(UiError_UnknownWorkspace value)? unknownWorkspace,
    TResult? Function(UiError_Transition value)? transition,
    TResult? Function(UiError_NoIdentity value)? noIdentity,
    TResult? Function(UiError_StoreLocked value)? storeLocked,
    TResult? Function(UiError_Crypto value)? crypto,
    TResult? Function(UiError_Storage value)? storage,
    TResult? Function(UiError_VaultLocked value)? vaultLocked,
    TResult? Function(UiError_Engine value)? engine,
    TResult? Function(UiError_Catalogue value)? catalogue,
    TResult? Function(UiError_Download value)? download,
    TResult? Function(UiError_CatalogueUnavailable value)? catalogueUnavailable,
    TResult? Function(UiError_Travel value)? travel,
    TResult? Function(UiError_TravelUnavailable value)? travelUnavailable,
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace() when unknownWorkspace != null:
        return unknownWorkspace(_that);
      case UiError_Transition() when transition != null:
        return transition(_that);
      case UiError_NoIdentity() when noIdentity != null:
        return noIdentity(_that);
      case UiError_StoreLocked() when storeLocked != null:
        return storeLocked(_that);
      case UiError_Crypto() when crypto != null:
        return crypto(_that);
      case UiError_Storage() when storage != null:
        return storage(_that);
      case UiError_VaultLocked() when vaultLocked != null:
        return vaultLocked(_that);
      case UiError_Engine() when engine != null:
        return engine(_that);
      case UiError_Catalogue() when catalogue != null:
        return catalogue(_that);
      case UiError_Download() when download != null:
        return download(_that);
      case UiError_CatalogueUnavailable() when catalogueUnavailable != null:
        return catalogueUnavailable(_that);
      case UiError_Travel() when travel != null:
        return travel(_that);
      case UiError_TravelUnavailable() when travelUnavailable != null:
        return travelUnavailable(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String field0)? unknownWorkspace,
    TResult Function(String field0)? transition,
    TResult Function()? noIdentity,
    TResult Function()? storeLocked,
    TResult Function(String field0)? crypto,
    TResult Function(String field0)? storage,
    TResult Function()? vaultLocked,
    TResult Function(String field0)? engine,
    TResult Function(String field0)? catalogue,
    TResult Function(String field0)? download,
    TResult Function()? catalogueUnavailable,
    TResult Function(String field0)? travel,
    TResult Function()? travelUnavailable,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace() when unknownWorkspace != null:
        return unknownWorkspace(_that.field0);
      case UiError_Transition() when transition != null:
        return transition(_that.field0);
      case UiError_NoIdentity() when noIdentity != null:
        return noIdentity();
      case UiError_StoreLocked() when storeLocked != null:
        return storeLocked();
      case UiError_Crypto() when crypto != null:
        return crypto(_that.field0);
      case UiError_Storage() when storage != null:
        return storage(_that.field0);
      case UiError_VaultLocked() when vaultLocked != null:
        return vaultLocked();
      case UiError_Engine() when engine != null:
        return engine(_that.field0);
      case UiError_Catalogue() when catalogue != null:
        return catalogue(_that.field0);
      case UiError_Download() when download != null:
        return download(_that.field0);
      case UiError_CatalogueUnavailable() when catalogueUnavailable != null:
        return catalogueUnavailable();
      case UiError_Travel() when travel != null:
        return travel(_that.field0);
      case UiError_TravelUnavailable() when travelUnavailable != null:
        return travelUnavailable();
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String field0) unknownWorkspace,
    required TResult Function(String field0) transition,
    required TResult Function() noIdentity,
    required TResult Function() storeLocked,
    required TResult Function(String field0) crypto,
    required TResult Function(String field0) storage,
    required TResult Function() vaultLocked,
    required TResult Function(String field0) engine,
    required TResult Function(String field0) catalogue,
    required TResult Function(String field0) download,
    required TResult Function() catalogueUnavailable,
    required TResult Function(String field0) travel,
    required TResult Function() travelUnavailable,
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace():
        return unknownWorkspace(_that.field0);
      case UiError_Transition():
        return transition(_that.field0);
      case UiError_NoIdentity():
        return noIdentity();
      case UiError_StoreLocked():
        return storeLocked();
      case UiError_Crypto():
        return crypto(_that.field0);
      case UiError_Storage():
        return storage(_that.field0);
      case UiError_VaultLocked():
        return vaultLocked();
      case UiError_Engine():
        return engine(_that.field0);
      case UiError_Catalogue():
        return catalogue(_that.field0);
      case UiError_Download():
        return download(_that.field0);
      case UiError_CatalogueUnavailable():
        return catalogueUnavailable();
      case UiError_Travel():
        return travel(_that.field0);
      case UiError_TravelUnavailable():
        return travelUnavailable();
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String field0)? unknownWorkspace,
    TResult? Function(String field0)? transition,
    TResult? Function()? noIdentity,
    TResult? Function()? storeLocked,
    TResult? Function(String field0)? crypto,
    TResult? Function(String field0)? storage,
    TResult? Function()? vaultLocked,
    TResult? Function(String field0)? engine,
    TResult? Function(String field0)? catalogue,
    TResult? Function(String field0)? download,
    TResult? Function()? catalogueUnavailable,
    TResult? Function(String field0)? travel,
    TResult? Function()? travelUnavailable,
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace() when unknownWorkspace != null:
        return unknownWorkspace(_that.field0);
      case UiError_Transition() when transition != null:
        return transition(_that.field0);
      case UiError_NoIdentity() when noIdentity != null:
        return noIdentity();
      case UiError_StoreLocked() when storeLocked != null:
        return storeLocked();
      case UiError_Crypto() when crypto != null:
        return crypto(_that.field0);
      case UiError_Storage() when storage != null:
        return storage(_that.field0);
      case UiError_VaultLocked() when vaultLocked != null:
        return vaultLocked();
      case UiError_Engine() when engine != null:
        return engine(_that.field0);
      case UiError_Catalogue() when catalogue != null:
        return catalogue(_that.field0);
      case UiError_Download() when download != null:
        return download(_that.field0);
      case UiError_CatalogueUnavailable() when catalogueUnavailable != null:
        return catalogueUnavailable();
      case UiError_Travel() when travel != null:
        return travel(_that.field0);
      case UiError_TravelUnavailable() when travelUnavailable != null:
        return travelUnavailable();
      case _:
        return null;
    }
  }
}

/// @nodoc

class UiError_UnknownWorkspace extends UiError {
  const UiError_UnknownWorkspace(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_UnknownWorkspaceCopyWith<UiError_UnknownWorkspace> get copyWith =>
      _$UiError_UnknownWorkspaceCopyWithImpl<UiError_UnknownWorkspace>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_UnknownWorkspace &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.unknownWorkspace(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_UnknownWorkspaceCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_UnknownWorkspaceCopyWith(UiError_UnknownWorkspace value,
          $Res Function(UiError_UnknownWorkspace) _then) =
      _$UiError_UnknownWorkspaceCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_UnknownWorkspaceCopyWithImpl<$Res>
    implements $UiError_UnknownWorkspaceCopyWith<$Res> {
  _$UiError_UnknownWorkspaceCopyWithImpl(this._self, this._then);

  final UiError_UnknownWorkspace _self;
  final $Res Function(UiError_UnknownWorkspace) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_UnknownWorkspace(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_Transition extends UiError {
  const UiError_Transition(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_TransitionCopyWith<UiError_Transition> get copyWith =>
      _$UiError_TransitionCopyWithImpl<UiError_Transition>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Transition &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.transition(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_TransitionCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_TransitionCopyWith(
          UiError_Transition value, $Res Function(UiError_Transition) _then) =
      _$UiError_TransitionCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_TransitionCopyWithImpl<$Res>
    implements $UiError_TransitionCopyWith<$Res> {
  _$UiError_TransitionCopyWithImpl(this._self, this._then);

  final UiError_Transition _self;
  final $Res Function(UiError_Transition) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Transition(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_NoIdentity extends UiError {
  const UiError_NoIdentity() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is UiError_NoIdentity);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError.noIdentity()';
  }
}

/// @nodoc

class UiError_StoreLocked extends UiError {
  const UiError_StoreLocked() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is UiError_StoreLocked);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError.storeLocked()';
  }
}

/// @nodoc

class UiError_Crypto extends UiError {
  const UiError_Crypto(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_CryptoCopyWith<UiError_Crypto> get copyWith =>
      _$UiError_CryptoCopyWithImpl<UiError_Crypto>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Crypto &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.crypto(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_CryptoCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_CryptoCopyWith(
          UiError_Crypto value, $Res Function(UiError_Crypto) _then) =
      _$UiError_CryptoCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_CryptoCopyWithImpl<$Res>
    implements $UiError_CryptoCopyWith<$Res> {
  _$UiError_CryptoCopyWithImpl(this._self, this._then);

  final UiError_Crypto _self;
  final $Res Function(UiError_Crypto) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Crypto(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_Storage extends UiError {
  const UiError_Storage(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_StorageCopyWith<UiError_Storage> get copyWith =>
      _$UiError_StorageCopyWithImpl<UiError_Storage>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Storage &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.storage(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_StorageCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_StorageCopyWith(
          UiError_Storage value, $Res Function(UiError_Storage) _then) =
      _$UiError_StorageCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_StorageCopyWithImpl<$Res>
    implements $UiError_StorageCopyWith<$Res> {
  _$UiError_StorageCopyWithImpl(this._self, this._then);

  final UiError_Storage _self;
  final $Res Function(UiError_Storage) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Storage(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_VaultLocked extends UiError {
  const UiError_VaultLocked() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is UiError_VaultLocked);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError.vaultLocked()';
  }
}

/// @nodoc

class UiError_Engine extends UiError {
  const UiError_Engine(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_EngineCopyWith<UiError_Engine> get copyWith =>
      _$UiError_EngineCopyWithImpl<UiError_Engine>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Engine &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.engine(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_EngineCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_EngineCopyWith(
          UiError_Engine value, $Res Function(UiError_Engine) _then) =
      _$UiError_EngineCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_EngineCopyWithImpl<$Res>
    implements $UiError_EngineCopyWith<$Res> {
  _$UiError_EngineCopyWithImpl(this._self, this._then);

  final UiError_Engine _self;
  final $Res Function(UiError_Engine) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Engine(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_Catalogue extends UiError {
  const UiError_Catalogue(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_CatalogueCopyWith<UiError_Catalogue> get copyWith =>
      _$UiError_CatalogueCopyWithImpl<UiError_Catalogue>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Catalogue &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.catalogue(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_CatalogueCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_CatalogueCopyWith(
          UiError_Catalogue value, $Res Function(UiError_Catalogue) _then) =
      _$UiError_CatalogueCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_CatalogueCopyWithImpl<$Res>
    implements $UiError_CatalogueCopyWith<$Res> {
  _$UiError_CatalogueCopyWithImpl(this._self, this._then);

  final UiError_Catalogue _self;
  final $Res Function(UiError_Catalogue) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Catalogue(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_Download extends UiError {
  const UiError_Download(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_DownloadCopyWith<UiError_Download> get copyWith =>
      _$UiError_DownloadCopyWithImpl<UiError_Download>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Download &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.download(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_DownloadCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_DownloadCopyWith(
          UiError_Download value, $Res Function(UiError_Download) _then) =
      _$UiError_DownloadCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_DownloadCopyWithImpl<$Res>
    implements $UiError_DownloadCopyWith<$Res> {
  _$UiError_DownloadCopyWithImpl(this._self, this._then);

  final UiError_Download _self;
  final $Res Function(UiError_Download) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Download(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_CatalogueUnavailable extends UiError {
  const UiError_CatalogueUnavailable() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_CatalogueUnavailable);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError.catalogueUnavailable()';
  }
}

/// @nodoc

class UiError_Travel extends UiError {
  const UiError_Travel(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_TravelCopyWith<UiError_Travel> get copyWith =>
      _$UiError_TravelCopyWithImpl<UiError_Travel>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Travel &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.travel(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_TravelCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_TravelCopyWith(
          UiError_Travel value, $Res Function(UiError_Travel) _then) =
      _$UiError_TravelCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_TravelCopyWithImpl<$Res>
    implements $UiError_TravelCopyWith<$Res> {
  _$UiError_TravelCopyWithImpl(this._self, this._then);

  final UiError_Travel _self;
  final $Res Function(UiError_Travel) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Travel(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_TravelUnavailable extends UiError {
  const UiError_TravelUnavailable() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_TravelUnavailable);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError.travelUnavailable()';
  }
}

// dart format on
