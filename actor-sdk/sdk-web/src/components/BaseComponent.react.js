import React, { Component, PropTypes } from 'react';
import en from '../i18n/en-US.js';
import _ from 'lodash';

class BaseComponent extends Component {
  getIntlMessage = key => {
      return _.get(en, "messages." + key, key);
  };               
}

export default BaseComponent;