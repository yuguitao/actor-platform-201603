/*
 * Copyright (C) 2015 Actor LLC. <https://actor.im>
 */

import 'babel-polyfill';
import '../utils/intl-polyfill';
import '../workers'

import RouterContainer from '../utils/RouterContainer';
import DelegateContainer from '../utils/DelegateContainer';
import SDKDelegate from './actor-sdk-delegate';
import { endpoints } from '../constants/ActorAppConstants'
import Pace from 'pace';

import React, { Component, PropTypes } from 'react';
import ReactDOM from 'react-dom';
import Router from 'react-router';
import IntlProvider from 'react-mixin';
import Actor from 'actor-js';

import { IntlMixin } from 'react-intl';
import crosstab from 'crosstab';

import LoginActionCreators from '../actions/LoginActionCreators';

import LoginStore from '../stores/LoginStore';

import DefaultDeactivated from '../components/Deactivated.react';
import DefaultLogin from '../components/Login.react';
import Main from '../components/Main.react';
import DefaultJoinGroup from '../components/JoinGroup.react';
import DefaultInstall from '../components/Install.react';

import { extendL18n, getIntlData } from '../l18n';

const { DefaultRoute, Route, RouteHandler } = Router;

Pace.start({
  ajax: false,
  restartOnRequestAfter: false,
  restartOnPushState: false
});

window.isJsAppLoaded = false;
window.jsAppLoaded = () => {
  window.isJsAppLoaded = true;
};

class App extends Component {
  static childContextTypes =  {
    delegate: PropTypes.object,
    isExperimental: PropTypes.bool
  };

  static propTypes =  {
    delegate: PropTypes.object,
    isExperimental: PropTypes.bool
  };

  getChildContext() {
    return {
      delegate: this.props.delegate,
      isExperimental: this.props.isExperimental
    };
  }

  constructor(props) {
    super(props);
  }

  render() {
    return <IntlProvider><RouteHandler/></IntlProvider>;
  }
}

/** Class represents ActorSKD itself */
class ActorSDK {
  /**
   * @constructor
   * @param {object} options - Object contains custom components, actions and localisation strings.
   */
  constructor(options = {}) {

    this.endpoints = (options.endpoints && options.endpoints.length > 0) ? options.endpoints : endpoints;
    this.isExperimental = options.isExperimental ? options.isExperimental : false;

    this.delegate = options.delegate ? options.delegate : new SDKDelegate();
    DelegateContainer.set(this.delegate);

    if (this.delegate.l18n) {
      extendL18n();
    }
  }

  _starter() {
    const ActorInitEvent = 'concurrentActorInit';

    if (crosstab.supported) {
      crosstab.on(ActorInitEvent, (msg) => {
        if (msg.origin !== crosstab.id && window.location.hash !== '#/deactivated') {
          window.location.assign('#/deactivated');
          window.location.reload();
        }
      });
    }

    const appRootElemet = document.getElementById('actor-web-app');

    if (window.location.hash !== '#/deactivated') {
      if (crosstab.supported) {
        crosstab.broadcast(ActorInitEvent, {});
      }

      window.messenger = Actor.create(this.endpoints);
    }

    const Login = this.delegate.components.login || DefaultLogin;
    const Deactivated = this.delegate.components.deactivated || DefaultDeactivated;
    const Install = this.delegate.components.install || DefaultInstall;
    const JoinGroup = this.delegate.components.joinGroup || DefaultJoinGroup;
    const intlData = getIntlData();
    
    ReactDOM.render(<Login/>, appRootElemet);

    // const routes = (
    //   <Route component={App} name="app" path="/">
    //     <Route component={Login} name="login" path="/auth"/>

    //     <Route component={Main} name="main" path="/im/:id"/>
    //     <Route component={JoinGroup} name="join" path="/join/:token"/>
    //     <Route component={Deactivated} name="deactivated" path="/deactivated"/>
    //     <Route component={Install} name="install" path="/install"/>

    //     <IndexRoute component={Main}/>
    //   </Route>
    // );

    // const router = Router.create(routes, Router.HashLocation);

    // RouterContainer.set(router);

    // router.run((Root) => React.render(<Root {...intlData} delegate={this.delegate} isExperimental={this.isExperimental}/>, appRootElemet));

    

    if (window.location.hash !== '#/deactivated') {
      if (LoginStore.isLoggedIn()) {
        LoginActionCreators.setLoggedIn({redirect: false});
      }
    }
  };

  /**
   * Start application
   */
  startApp() {
    if (window.isJsAppLoaded) {
      this._starter();
    } else {
      window.jsAppLoaded = this._starter.bind(this);
    }
  }
}

export default ActorSDK;
