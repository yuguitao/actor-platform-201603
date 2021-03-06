/*
* Copyright (C) 2015-2016 Actor LLC. <https://actor.im>
*/

import React, {Component, PropTypes} from 'react';
import { FormattedMessage } from 'react-intl';
import classnames from 'classnames';
import { CallStates } from '../../constants/ActorAppConstants';

import AvatarItem from '../common/AvatarItem.react';

class CallBody extends Component {
  static propTypes = {
    peerInfo: React.PropTypes.object,
    callState: PropTypes.oneOf([
      CallStates.CALLING,
      CallStates.IN_PROGRESS,
      CallStates.CONNECTING,
      CallStates.ENDED
    ])
  };

  renderAvatarAnimation() {
    const {callState} = this.props;
    switch (this.props.callState) {
      case CallStates.CALLING:
      case CallStates.CONNECTING:
          return (
            <div className="call__avatar__rings">
              <div/><div/><div/>
            </div>
          );
        break;
      default:
        return null;
    }
  }

  render() {
    const {peerInfo} = this.props;
    if (!peerInfo) {
      return null;
    }

    return (
      <div className="call__body">
        <div className="call__avatar">
          <AvatarItem
            size="big"
            image={peerInfo.avatar}
            title={peerInfo.name}
            placeholder={peerInfo.placeholder}
          />
          {this.renderAvatarAnimation()}
        </div>
        <h3 className="call__title">{peerInfo.name}</h3>
      </div>
    );
  }
}

export default CallBody;
